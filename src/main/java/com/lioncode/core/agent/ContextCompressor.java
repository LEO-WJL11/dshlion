package com.lioncode.core.agent;

import com.lioncode.model.adapter.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩：对话快把模型的上下文窗口塞满时，把"中间那段"压成一份摘要。
 *
 * <p>【为什么必须要有】在这之前项目里**没有任何上下文管理**：每轮都把整个会话历史原样
 * 发给模型，历史涨到超过 n_ctx 时，llama-server 直接回 400（"exceeds the available context size"），
 * 用户看到的就是"模型调用失败"，而且这个会话**永久坏掉**——后面每条消息都还是超长。
 * 本机 llama-server 是 262144 上下文，正常聊天碰不到，但长任务（几十个工具结果、大文件内容）
 * 是真能堆上去的，云模型那边窗口更小，更容易撞。</p>
 *
 * <p>做法（**抽取式**摘要，不额外调模型）：
 * 保留开头所有 system 消息 + 最后 keepRecent 条，中间那段换成一条
 * "【早前对话摘要】"的 user 消息。为什么用抽取式而不是让模型总结：
 * 本地 11 token/s，让模型总结一次要十几秒到几十秒，而压缩是**在用户等待的链路上**发生的；
 * 抽取式是纯字符串操作，微秒级，而且不会像小模型总结那样丢关键事实
 * （实测 4bit 模型总结常把路径/端口号写错，压完反而更糟）。
 * 摘要里保留的是：用户说过的话、做过哪些工具调用（名字+关键参数）、工具结果要点、助手结论。</p>
 *
 * <p>注意一个坑：**不能把 assistant(tool_calls) 和它后面的 tool 结果切散**，
 * 否则请求直接 400（tool 消息必须紧跟对应的 tool_calls）。所以切点会往前/后对齐到安全边界。</p>
 */
public final class ContextCompressor {

    private ContextCompressor() {
    }

    /** 压缩结果 */
    public record Result(List<ChatMessage> messages, boolean compressed,
                         int tokensBefore, int tokensAfter, int droppedMessages) {
    }

    /** 单条消息在摘要里最多留多少字符（用户/助手的原话，留多了摘要本身就爆了） */
    private static final int DIGEST_USER_CHARS = 240;
    private static final int DIGEST_ASSISTANT_CHARS = 200;
    private static final int DIGEST_TOOL_CHARS = 140;
    /** 摘要整体上限（字符）。摘要本身也要花 token，不能无限长。 */
    private static final int DIGEST_MAX_CHARS = 4000;
    /** 保留尾部里，单条工具结果最多留多少字符（防止一条超大输出把预算吃光） */
    private static final int TAIL_TOOL_MAX_CHARS = 8000;

    /**
     * 估算一段文本的 token 数。
     *
     * <p>不引 tokenizer（本地模型的分词器在 llama-server 里，拿不到）；
     * 用经验公式：中日韩字符 ≈ 1 token/字，其余 ≈ 1 token/3.5 字符。
     * Qwen 对中文差不多就是这个量级，估算只用来决定"要不要压缩"，留了 20% 余量。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF00 && c <= 0xFFEF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 3.5);
    }

    /** 整份消息列表的 token 估算（含每条消息的固定开销）。 */
    public static int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += 8;   // role/分隔符等固定开销
            total += estimateTokens(m.content());
            if (m.toolCalls() != null) {
                for (ChatMessage.ToolCall tc : m.toolCalls()) {
                    total += 16 + estimateTokens(tc.name())
                        + estimateTokens(String.valueOf(tc.arguments()));
                }
            }
        }
        return total;
    }

    /**
     * 按预算裁剪消息。
     *
     * @param messages    当前要发出去的消息（第一条通常是 system）
     * @param limitTokens 预算上限；&le;0 表示不压缩
     * @param keepRecent  尾部保留多少条（压不动时会自动往下调）
     */
    public static Result fit(List<ChatMessage> messages, int limitTokens, int keepRecent) {
        int before = estimateTokens(messages);
        if (limitTokens <= 0 || before <= limitTokens || messages.size() <= 2) {
            return new Result(messages, false, before, before, 0);
        }

        // 1) 头部：连续的 system 消息全部保留（Qwen 模板要求 system 必须在最前）
        int head = 0;
        while (head < messages.size() && "system".equals(messages.get(head).role())) {
            head++;
        }

        // 2) 尾部越压越少，直到进预算或者只剩最后两条
        Result best = null;
        for (int keep : new int[] {keepRecent, Math.max(4, keepRecent / 2), 4, 2}) {
            int tailStart = Math.max(head, messages.size() - keep);
            // 对齐到安全边界：第一条不能是 tool（否则是"孤儿"工具结果，服务端会 400）
            while (tailStart > head && "tool".equals(messages.get(tailStart).role())) {
                tailStart--;
            }
            List<ChatMessage> digestSource = messages.subList(head, tailStart);
            List<ChatMessage> out = new ArrayList<>(messages.subList(0, head));
            // 摘要本身也要占预算：预算越小，摘要必须越短，否则"压完还是超"。
            // 中文大约 1 字 1 token，所以按 1/4 预算给字符数，最少 200 字。
            String digest = digest(digestSource, Math.min(DIGEST_MAX_CHARS,
                Math.max(200, limitTokens / 4)));
            if (!digest.isBlank()) {
                // 用 user 消息而不是 system：Qwen 模板对"夹在中间的 system"直接抛异常
                out.add(ChatMessage.user("【早前对话摘要（系统自动压缩，" + digestSource.size()
                    + " 条历史消息已经折叠，原文不再发送。需要细节就用工具重新读一次文件/重新跑命令）】\n"
                    + digest));
            }
            for (ChatMessage m : messages.subList(tailStart, messages.size())) {
                out.add(trimToolResult(m));
            }
            int after = estimateTokens(out);
            Result candidate = new Result(out, true, before, after, digestSource.size());
            if (best == null || candidate.tokensAfter() < best.tokensAfter()) {
                best = candidate;
            }
            if (after <= limitTokens) {
                return candidate;
            }
        }
        // 3) 实在压不到预算以内（预算小到连摘要都放不下）：**也返回压过的版本**。
        //    返回原文等于保证服务端 400（整个会话直接废掉），返回压缩版至少还能继续对话 ——
        //    这是"两害相权取其轻"，不是理想状态，所以日志里会记真实 token 数。
        if (best != null && best.tokensAfter() < before) {
            return best;
        }
        // 压不动（比如系统提示词本身就超预算、或者消息太少没有可折叠的中间段）：
        // 老老实实按原样返回，**不要**报一个"压缩了但一个 token 都没省"的假事件 ——
        // 压测里就是这么看到"3 次压缩、before==after"，日志和事件流全被噪音污染。
        return new Result(messages, false, before, before, 0);
    }

    /** 超大的工具结果就地截断（保留头部，尾部给一句说明）。 */
    private static ChatMessage trimToolResult(ChatMessage m) {
        if (!"tool".equals(m.role()) || m.content() == null || m.content().length() <= TAIL_TOOL_MAX_CHARS) {
            return m;
        }
        String cut = m.content().substring(0, TAIL_TOOL_MAX_CHARS)
            + "\n…（结果过长已截断，原始长度 " + m.content().length() + " 字符）";
        return ChatMessage.toolResult(m.toolCallId(), cut);
    }

    /** 把一批消息压成一段人话摘要（抽取式，不调模型）。 */
    static String digest(List<ChatMessage> source, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : source) {
            String role = m.role();
            String content = m.content() == null ? "" : m.content().replaceAll("\\s+", " ").trim();
            if ("user".equals(role)) {
                if (content.isEmpty()) {
                    continue;
                }
                sb.append("· 用户说：").append(cut(content, DIGEST_USER_CHARS)).append('\n');
            } else if ("assistant".equals(role)) {
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    for (ChatMessage.ToolCall tc : m.toolCalls()) {
                        sb.append("· 调用工具：").append(tc.name())
                          .append('(').append(cut(String.valueOf(tc.arguments()), 120)).append(")\n");
                    }
                }
                if (!content.isEmpty()) {
                    sb.append("· 助手结论：").append(cut(content, DIGEST_ASSISTANT_CHARS)).append('\n');
                }
            } else if ("tool".equals(role)) {
                if (content.isEmpty()) {
                    continue;
                }
                sb.append("· 工具结果：").append(cut(content, DIGEST_TOOL_CHARS)).append('\n');
            }
            if (sb.length() > maxChars) {
                // 超了就**保留最近的**（前面的丢），因为越靠近现在越有用
                String s = sb.toString();
                int keepFrom = s.indexOf('\n', s.length() - maxChars);
                sb.setLength(0);
                sb.append("…（更早的摘要已省略）\n")
                  .append(keepFrom > 0 ? s.substring(keepFrom + 1) : s);
                break;
            }
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 自检：{@code java -cp target/classes com.lioncode.core.agent.ContextCompressor}
     *
     * <p>压测（"上下文满了会怎样"）的第一层：这一层不依赖模型、不依赖网络，
     * 纯函数、毫秒级，专门盯三条硬规则 ——
     * ① 超预算一定压到预算以内；② system 必须在最前；③ 绝不能把
     * assistant(tool_calls) 和它的 tool 结果切散（切散服务端直接 400）。
     */
    public static void main(String[] args) {
        int pass = 0;
        int fail = 0;

        // ---- 造一段"长任务"历史：20 轮工具调用，每轮结果都很大 ----
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("你是助手。"));
        for (int i = 0; i < 20; i++) {
            messages.add(ChatMessage.user("第 " + i + " 步：读一下文件 f" + i + ".txt，我关心端口号 " + i + "。"));
            messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.ToolCall("call" + i, "read_file",
                    java.util.Map.of("path", "f" + i + ".txt")))));
            messages.add(ChatMessage.toolResult("call" + i,
                "内容".repeat(400) + " 端口 8788"));
        }
        messages.add(ChatMessage.user("总结一下"));

        int before = estimateTokens(messages);
        System.out.println("压缩前：" + messages.size() + " 条消息，约 " + before + " token");

        Result r = fit(messages, 2000, 8);
        System.out.println("压缩后：" + r.messages().size() + " 条，约 " + r.tokensAfter()
            + " token，折叠 " + r.droppedMessages() + " 条");

        // ① 必须降到预算以内
        boolean ok = r.compressed() && r.tokensAfter() <= 2000;
        System.out.println((ok ? "OK  " : "FAIL") + " ① 压缩后必须在预算内（2000）");
        pass += ok ? 1 : 0;
        fail += ok ? 0 : 1;

        // ② 第一条必须还是 system
        boolean sysFirst = !r.messages().isEmpty() && "system".equals(r.messages().get(0).role());
        System.out.println((sysFirst ? "OK  " : "FAIL") + " ② system 消息必须在最前");
        pass += sysFirst ? 1 : 0;
        fail += sysFirst ? 0 : 1;

        // ③ tool 结果不能变成孤儿（前一条必须是对应的 assistant(tool_calls)）
        boolean noOrphan = true;
        for (int i = 0; i < r.messages().size(); i++) {
            ChatMessage m = r.messages().get(i);
            if (!"tool".equals(m.role())) {
                continue;
            }
            if (i == 0) {
                noOrphan = false;
                break;
            }
            ChatMessage prev = r.messages().get(i - 1);
            boolean matched = "assistant".equals(prev.role()) && prev.toolCalls() != null
                && prev.toolCalls().stream().anyMatch(tc -> tc.id().equals(m.toolCallId()));
            if (!matched) {
                noOrphan = false;
                break;
            }
        }
        System.out.println((noOrphan ? "OK  " : "FAIL") + " ③ 工具结果不能与它的调用被切散");
        pass += noOrphan ? 1 : 0;
        fail += noOrphan ? 0 : 1;

        // ④ 摘要里要留住用户说过的事（"端口"这种关键事实不能被吃掉）
        String digest = r.messages().stream()
            .filter(m -> m.content() != null && m.content().contains("早前对话摘要"))
            .map(ChatMessage::content).findFirst().orElse("");
        boolean keeps = digest.contains("用户说");
        System.out.println((keeps ? "OK  " : "FAIL") + " ④ 摘要里保留了用户说过的话");
        pass += keeps ? 1 : 0;
        fail += keeps ? 0 : 1;

        // ⑤ 不超预算时绝不能动消息（压缩本身不能有副作用）
        List<ChatMessage> small = new ArrayList<>(messages.subList(0, 3));
        Result none = fit(small, 100000, 8);
        boolean untouched = !none.compressed() && none.messages() == small;
        System.out.println((untouched ? "OK  " : "FAIL") + " ⑤ 没超预算时原样返回、不复制不改动");
        pass += untouched ? 1 : 0;
        fail += untouched ? 0 : 1;

        // ⑥ 极端：预算小到只剩"摘要 + 最近两条"也必须收敛
        Result tiny = fit(messages, 300, 16);
        boolean converged = tiny.compressed() && tiny.tokensAfter() < before / 4;
        System.out.println((converged ? "OK  " : "FAIL") + " ⑥ 极小预算下也要收敛（"
            + before + " → " + tiny.tokensAfter() + " token）");
        pass += converged ? 1 : 0;
        fail += converged ? 0 : 1;

        System.out.printf("上下文压缩自检：%d 通过 / %d 失败%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
