package com.lioncode.core.context;

import com.lioncode.core.plugin.skill.SkillDefinition;
import com.lioncode.core.plugin.skill.SkillRepository;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.ConversationMessage;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ 引用的展开器：把用户消息里的 {@code @file:} / {@code @history:} / {@code @skill:}
 * 就地换成真实上下文，再交给 Agent 主循环。
 *
 * <p>【为什么在 ChatController 里展开，而不是走 AgentSpi.transformUserMessage】
 * 两条路都能通，但 SPI 那条是"全局改写"，谁调用 processMessage 都会被改写（预热服务、
 * 提示词预览这些也会路过），而且 SPI 里拿不到"这条消息带了几个引用"的完整信息去写事件。
 * 放在 ChatController 更稳：入口唯一、顺序明确（展开 → 写事件 → 入队），
 * 展开失败还能原样把用户的话发下去。</p>
 *
 * <p>语法（前端补全给的 insert 就是这个格式）：
 * <ul>
 *   <li>{@code @file:src/main/java/X.java} —— 文件内容（≤200 行 / 20KB，超出明确标注截断）</li>
 *   <li>{@code @history:<会话id 或 关键词>} —— 那个会话最近的 ≤30 条消息</li>
 *   <li>{@code @skill:<技能id>} 或 {@code /skill:<技能id>} —— 技能正文（用户点名要用的）</li>
 * </ul>
 *
 * <p>所有展开都**保留原 token**，内容紧跟在 token 后面用 {@code --- 引用开始/结束 ---} 包起来，
 * 这样模型既知道用户引用了什么，也不会把文件内容和用户的话混在一起。</p>
 */
@Component
public class MentionResolver {

    private static final Logger log = LoggerFactory.getLogger(MentionResolver.class);

    /** 一条引用最多给多少行文件内容：给多了会挤掉对话历史，模型反而看不见问题本身。 */
    private static final int MAX_FILE_LINES = 200;
    private static final int MAX_FILE_BYTES = 20 * 1024;
    /** 读文件时最多读这么多字节（防止用户 @ 一个几百 MB 的日志把内存打爆）。 */
    private static final int FILE_READ_CAP = 512 * 1024;
    private static final int MAX_DIR_ENTRIES = 100;

    private static final int MAX_HISTORY_MESSAGES = 30;
    private static final int MAX_HISTORY_BYTES = 20 * 1024;
    private static final int MAX_MESSAGE_CHARS = 800;

    private static final int MAX_SKILL_BYTES = 40 * 1024;

    /** 一轮消息里所有引用的总量上限：再多就没有"上下文"可言了，纯粹烧 token。 */
    private static final int MAX_TOTAL_BYTES = 80 * 1024;

    /**
     * 引用语法。
     *
     * <p>路径/关键词取非空白串（前端 insert 出来的路径没有空格）；末尾的中文标点和小括号
     * 会被 {@link #trimPunct} 修掉 —— 用户写"看看 @file:a.txt 对不对"时，
     * 空格自然断开，而写"（见 @file:a.txt）"这种就要靠修剪。</p>
     */
    private static final Pattern MENTION = Pattern.compile(
        "@(file|history|skill):([^\\s]+)|/skill:([A-Za-z0-9_.\\-]+)");

    /** 尾巴上这些字符算"用户的话"，不算引用的一部分。 */
    private static final String TRAILING_PUNCT = "，。；：、！？）】」》”’)]}>\"',;";

    private final SkillRepository skillRepository;
    private final SessionManager sessionManager;
    private final ConversationHistory conversationHistory;
    private final ContextRoots roots;

    public MentionResolver(SkillRepository skillRepository, SessionManager sessionManager,
                           ConversationHistory conversationHistory, ContextRoots roots) {
        this.skillRepository = skillRepository;
        this.sessionManager = sessionManager;
        this.conversationHistory = conversationHistory;
        this.roots = roots;
    }

    /**
     * 展开一条用户消息里的所有引用。
     *
     * @return 展开后的消息 + 展开记录（记录用来写事件、给界面提示）
     */
    public Expansion expand(String sessionId, String message) {
        if (message == null || message.isBlank() || !hasMention(message)) {
            return new Expansion(message, List.of());
        }
        Path root = roots.resolve(sessionId, null);
        Matcher m = MENTION.matcher(message);
        StringBuilder out = new StringBuilder(message.length() + 1024);
        List<Note> notes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int last = 0;
        int budget = MAX_TOTAL_BYTES;

        while (m.find()) {
            String kind = m.group(1) != null ? m.group(1) : "skill";
            String arg = trimPunct(m.group(1) != null ? m.group(2) : m.group(3));
            String token = m.group();
            // 原样保留用户写的 token：模型要知道"这段内容是用户引用的"，用户回看也不困惑
            out.append(message, last, m.end());
            last = m.end();

            Block block;
            if (arg.isEmpty()) {
                block = fail(token, "引用后面没写名字（例如 @file:src/App.java）");
            } else if (!seen.add(kind + ":" + arg)) {
                block = plain(token, "（同一个引用上面已经展开过，这里不重复贴一遍）");
            } else if (budget <= 0) {
                block = fail(token, "本轮引用内容已达上限（" + (MAX_TOTAL_BYTES / 1024)
                    + "KB），这一条没有展开。需要的话下一条消息单独引用它");
            } else {
                block = switch (kind) {
                    case "file" -> expandFile(token, arg, root);
                    case "history" -> expandHistory(token, arg);
                    default -> expandSkill(token, arg);
                };
                budget -= block.text().length();
            }
            out.append('\n').append(block.text()).append('\n');
            notes.add(block.note());
        }
        out.append(message, last, message.length());
        return new Expansion(out.toString(), notes);
    }

    /** 消息里有没有引用记号（没有就整条快路径返回，省掉正则和读盘）。 */
    public static boolean hasMention(String message) {
        return message != null && (message.contains("@file:") || message.contains("@history:")
            || message.contains("@skill:") || message.contains("/skill:"));
    }

    // ------------------------------------------------------------------
    // @file:
    // ------------------------------------------------------------------

    private Block expandFile(String token, String arg, Path root) {
        Path target = roots.contain(root, arg);
        if (target == null) {
            return fail(token, "路径在工作区之外，已拒绝读取：" + arg + "（当前工作区/根目录：" + root + "）");
        }
        if (!Files.exists(target)) {
            return fail(token, "文件不存在：" + arg + "（根目录：" + root + "）" + similarHint(target));
        }
        if (Files.isDirectory(target)) {
            return expandDirectory(token, arg, target, root);
        }
        if (!Files.isRegularFile(target)) {
            return fail(token, "不是普通文件（可能是设备/管道）：" + arg);
        }

        String text;
        boolean cutBySize;
        try {
            byte[] bytes = readCapped(target, FILE_READ_CAP);
            cutBySize = Files.size(target) > bytes.length;
            text = decode(bytes);
        } catch (Exception e) {
            log.warn("@file 读取失败: {} - {}", target, e.getMessage());
            return fail(token, "读取失败：" + arg + " —— " + e.getMessage());
        }

        String[] all = text.split("\r?\n", -1);
        int totalLines = all.length;
        StringBuilder body = new StringBuilder();
        int usedBytes = 0;
        int taken = 0;
        for (int i = 0; i < all.length && taken < MAX_FILE_LINES; i++) {
            int len = all[i].getBytes(StandardCharsets.UTF_8).length + 1;
            if (usedBytes + len > MAX_FILE_BYTES) {
                break;
            }
            body.append(all[i]).append('\n');
            usedBytes += len;
            taken++;
        }
        boolean truncated = taken < totalLines || cutBySize;
        String display = roots.displayPath(root, target);

        StringBuilder sb = new StringBuilder();
        sb.append("--- 引用开始：文件 ").append(display).append("（第 1-").append(taken).append(" 行，共 ")
          .append(totalLines).append(" 行");
        if (truncated) {
            sb.append("，**已截断**：只给了前 ").append(taken).append(" 行 / ")
              .append(MAX_FILE_BYTES / 1024).append("KB，需要更多就再引用一次并说明要看哪一段");
        }
        sb.append("）---\n");
        sb.append(body);
        sb.append("--- 引用结束：文件 ").append(display).append(" ---");

        String detail = "文件 " + display + "（" + taken + "/" + totalLines + " 行"
            + (truncated ? "，已截断" : "") + "）";
        return new Block(sb.toString(), new Note("file", token, detail, true));
    }

    private Block expandDirectory(String token, String arg, Path dir, Path root) {
        String display = roots.displayPath(root, dir);
        StringBuilder sb = new StringBuilder();
        sb.append("--- 引用开始：目录 ").append(display).append(" ---\n");
        int count = 0;
        try (var list = Files.list(dir)) {
            List<Path> entries = list.sorted().limit(MAX_DIR_ENTRIES).toList();
            for (Path p : entries) {
                sb.append(Files.isDirectory(p) ? "[DIR]  " : "[FILE] ").append(p.getFileName()).append('\n');
                count++;
            }
            if (count == 0) {
                sb.append("（空目录）\n");
            }
        } catch (Exception e) {
            return fail(token, "目录读取失败：" + arg + " —— " + e.getMessage());
        }
        sb.append("（这是目录不是文件；要某个文件就 @file:它的路径）\n");
        sb.append("--- 引用结束：目录 ").append(display).append(" ---");
        return new Block(sb.toString(), new Note("file", token, "目录 " + display + "（" + count + " 项）", true));
    }

    /** 文件不存在时给几个相近的名字（和 read_file 的 similarPathHint 一个思路）。 */
    private String similarHint(Path target) {
        try {
            Path dir = target.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                return "";
            }
            String want = target.getFileName().toString().toLowerCase(Locale.ROOT);
            String stem = want.contains(".") ? want.substring(0, want.lastIndexOf('.')) : want;
            List<String> near = new ArrayList<>();
            try (var list = Files.list(dir)) {
                for (Path p : list.toList()) {
                    String n = p.getFileName().toString();
                    String lower = n.toLowerCase(Locale.ROOT);
                    if (lower.contains(stem) || stem.contains(lower)) {
                        near.add(n);
                    }
                    if (near.size() >= 5) {
                        break;
                    }
                }
            }
            return near.isEmpty() ? "" : "。同目录下有这些相近的名字：" + String.join("、", near);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // @history:
    // ------------------------------------------------------------------

    private Block expandHistory(String token, String arg) {
        String id = resolveHistoryId(arg);
        if (id == null) {
            List<String> candidates = sessionManager.getAllSessions().stream()
                .limit(8)
                .map(s -> s.displayName() + "(" + shortId(s.sessionId()) + ")")
                .toList();
            return fail(token, "没找到历史对话：" + arg + "（可用的有："
                + (candidates.isEmpty() ? "当前没有历史对话" : String.join("、", candidates)) + "）");
        }
        List<ConversationMessage> history = conversationHistory.getHistory(id);
        if (history.isEmpty()) {
            // 内存里没有就去磁盘捞一次：历史文件是按会话存的，重启后按需加载也就够了
            conversationHistory.loadFromDisk(id);
            history = conversationHistory.getHistory(id);
        }
        if (history.isEmpty()) {
            return fail(token, "历史对话 " + arg + " 里没有任何消息");
        }

        String label = sessionManager.getSession(id).map(SessionManager.Session::displayName)
            .orElse("会话 " + shortId(id));
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<ConversationMessage> recent = history.subList(from, history.size());

        StringBuilder sb = new StringBuilder();
        sb.append("--- 引用开始：历史会话「").append(label).append("」(").append(shortId(id))
          .append(")，最近 ").append(recent.size()).append(" 条消息 ---\n");
        // 这句必须写：不写清楚，模型经常把历史会话里的内容当成当前对话的一部分，
        // 甚至去回答历史会话里那个已经结束的问题
        sb.append("注意：下面是**另一个历史会话**的内容（不是当前会话），只作参考。");
        sb.append("不要把它当成当前对话，也不要回复其中的问题；当前用户的问题在引用块之外。\n\n");

        int usedBytes = 0;
        int included = 0;
        for (ConversationMessage msg : recent) {
            String line = renderMessage(msg);
            if (line == null || line.isBlank()) {
                continue;
            }
            int len = line.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + len > MAX_HISTORY_BYTES) {
                sb.append("…（历史内容过长，后面的消息省略了）\n");
                break;
            }
            sb.append(line).append('\n');
            usedBytes += len;
            included++;
        }
        sb.append("--- 引用结束：历史会话「").append(label).append("」(").append(shortId(id)).append(") ---");

        String detail = "历史对话「" + label + "」(" + shortId(id) + ")，" + included + " 条消息";
        return new Block(sb.toString(), new Note("history", token, detail, true));
    }

    /** 支持直接给会话 id，也支持给关键词（标题/首条用户消息里包含它）。 */
    private String resolveHistoryId(String arg) {
        if (sessionManager.getSession(arg).isPresent()) {
            return arg;
        }
        String q = arg.toLowerCase(Locale.ROOT);
        String best = null;
        int bestScore = 0;
        for (SessionManager.Session s : sessionManager.getAllSessions()) {
            int score = 0;
            String id = s.sessionId() == null ? "" : s.sessionId().toLowerCase(Locale.ROOT);
            if (id.equals(q)) {
                score = 120;
            } else if (id.startsWith(q)) {
                score = 100;
            } else if (id.contains(q)) {
                score = 80;
            }
            String name = s.displayName() == null ? "" : s.displayName().toLowerCase(Locale.ROOT);
            if (!name.isBlank() && name.contains(q)) {
                score = Math.max(score, 90);
            }
            String first = firstUserMessage(s.sessionId());
            if (!first.isBlank() && first.toLowerCase(Locale.ROOT).contains(q)) {
                score = Math.max(score, 70);
            }
            if (score > bestScore) {
                bestScore = score;
                best = s.sessionId();
            }
        }
        return best;
    }

    private String firstUserMessage(String sessionId) {
        for (ConversationMessage m : conversationHistory.getHistory(sessionId)) {
            if ("user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return "";
    }

    /** 把一条消息压成紧凑的一两行（历史上下文是"参考"，不需要原文照搬）。 */
    private String renderMessage(ConversationMessage m) {
        String role = m.role() == null ? "?" : m.role();
        switch (role) {
            case "user" -> {
                return "[用户] " + compact(m.content(), MAX_MESSAGE_CHARS);
            }
            case "assistant" -> {
                StringBuilder sb = new StringBuilder();
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    List<String> names = m.toolCalls().stream()
                        .map(tc -> tc.name() == null ? "?" : tc.name()).toList();
                    sb.append("[助手→调用工具] ").append(String.join("、", names));
                    if (m.content() != null && !m.content().isBlank()) {
                        sb.append("；说明：").append(compact(m.content(), 200));
                    }
                } else {
                    sb.append("[助手] ").append(compact(m.content(), MAX_MESSAGE_CHARS));
                }
                return sb.toString();
            }
            case "tool" -> {
                return "[工具结果:" + (m.toolName() == null ? "?" : m.toolName()) + "] "
                    + compact(m.content(), 300);
            }
            case "system" -> {
                return null;   // 系统消息是提示词噪音，引进来没意义
            }
            default -> {
                return "[" + role + "] " + compact(m.content(), 200);
            }
        }
    }

    // ------------------------------------------------------------------
    // @skill: / /skill:
    // ------------------------------------------------------------------

    private Block expandSkill(String token, String arg) {
        String reason = skillRepository.unusableReason(arg);
        if (reason != null) {
            return fail(token, reason);
        }
        SkillDefinition def = skillRepository.findEnabled(arg).orElseThrow();
        String body = def.body().trim();
        boolean cut = body.length() > MAX_SKILL_BYTES;
        if (cut) {
            body = body.substring(0, MAX_SKILL_BYTES) + "\n…（技能正文过长，已截断）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- 引用开始：技能 ").append(def.id()).append("（").append(def.displayName()).append("）---\n");
        sb.append("用户点名要用这个技能，本轮请按下面的指令执行：\n\n");
        sb.append(body).append('\n');
        sb.append("--- 引用结束：技能 ").append(def.id()).append(" ---");
        return new Block(sb.toString(),
            new Note("skill", token, "技能 " + def.id() + "（" + def.displayName() + "）的完整指令", true));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private Block fail(String token, String reason) {
        String text = "--- 引用失败：" + token + " ---\n原因：" + reason + "\n--- 引用结束 ---";
        return new Block(text, new Note("error", token, reason, false));
    }

    private Block plain(String token, String text) {
        return new Block(text, new Note("skip", token, "重复引用，未重复展开", true));
    }

    private static String trimPunct(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        while (!out.isEmpty() && TRAILING_PUNCT.indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1);
        }
        return out.trim();
    }

    private static String shortId(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String compact(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 读文件，最多读 cap 个字节（防止 @ 一个大文件把堆打爆）。 */
    private static byte[] readCapped(Path file, int cap) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(cap);
        }
    }

    /** 容错解码：UTF-8 → GBK（和工具层、技能解析保持一致，Windows 上 ANSI 文件很常见）。 */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception notUtf8) {
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        }
    }

    /** 一段展开结果 + 对应的展开记录。 */
    private record Block(String text, Note note) {
    }

    /**
     * 展开记录：给事件流和界面用（"已展开 @file:x（120 行）"）。
     *
     * @param type   file / history / skill / error / skip
     * @param token  用户写的原文（@file:xxx）
     * @param detail 一句人话说明
     * @param ok     是否成功
     */
    public record Note(String type, String token, String detail, boolean ok) {
    }

    /** 展开结果。 */
    public record Expansion(String message, List<Note> notes) {
        public boolean changed() {
            return notes != null && !notes.isEmpty();
        }

        /** 事件摘要：已展开 @file:a.txt（120 行）；已展开 @skill:backend */
        public String summary() {
            if (notes == null || notes.isEmpty()) {
                return "";
            }
            Set<String> parts = new LinkedHashSet<>();
            for (Note n : notes) {
                if (n.ok() && !"skip".equals(n.type())) {
                    parts.add("已展开 " + n.token() + "（" + n.detail() + "）");
                } else if (!n.ok()) {
                    parts.add("展开失败 " + n.token() + "：" + n.detail());
                }
            }
            return String.join("；", parts);
        }
    }
}
