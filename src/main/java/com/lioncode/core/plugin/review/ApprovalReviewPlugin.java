package com.lioncode.core.plugin.review;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginKind;
import com.lioncode.core.plugin.PluginSettings;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 自动授权审查插件：动手之前，先让<b>另一个模型</b>看一眼这次工具调用该不该放行。
 *
 * <p>它解决的是"审批弹窗太多了"这件事：用户不可能每条命令都点一次同意，
 * 但完全免审批又不敢。折中办法是把"要不要批准"交给一个审查模型 —— 它只回
 * {@code ALLOW} 或 {@code DENY: 理由}，危险动作拦下来、日常动作直接放行。</p>
 *
 * <p><b>本轮做到哪一步</b>：配置（用哪个提供商/模型、审哪些工具、开不开）、
 * 判定骨架（{@link #check}）、给审查模型用的提示词（{@link #buildReviewPrompt}）、
 * 以及拦截时回给模型的话（{@link #denyMessage}）。
 * <b>真正的"发一次对话"不在插件里做</b> —— 那需要拿到 AgentLoop 正在用的模型适配器、
 * 会话与用量统计，插件自己另起一套会跟主循环抢资源、也算不清账。
 * 接线点见类尾的 TODO。</p>
 */
@Component
public class ApprovalReviewPlugin implements Plugin {

    public static final String PLUGIN_ID = "plugin.approval-review";

    /**
     * 默认要审查的工具：能改文件系统/能执行任意命令/能动远端仓库的那些。
     *
     * <p>读文件、算 hash、格式化 JSON 这类纯计算不审 —— 全审等于每条都慢一倍，
     * 用户很快就会把这个插件关掉，那还不如一开始就只审危险的。</p>
     */
    public static final Set<String> DEFAULT_REVIEW_TOOLS = Set.of(
        "execute_command", "run_background", "stop_background",
        "delete_file", "move_file", "change_permissions",
        "git_reset", "git_stash", "git_remote",
        "download_file", "http_post");

    private final PluginSettings settings;

    public ApprovalReviewPlugin(PluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "approval_review";
    }

    @Override
    public String getDisplayName() {
        return "自动授权审查";
    }

    @Override
    public String getDescription() {
        return "执行危险工具前，让另一个模型对话审核该不该放行（用哪个模型可配置）";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.APPROVAL_REVIEW;
    }

    /**
     * 默认关闭。
     *
     * <p>它会在每次危险调用前多打一次模型对话 —— 对本地模型来说那是实打实的几十秒。
     * 这种"会明显改变体感"的能力必须用户主动开，不能升级完就默认生效。</p>
     */
    @Override
    public boolean isEnabledByDefault() {
        return false;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 审查用的提供商（空 = 跟主 Agent 一样） */
    public String provider() {
        return settings.stringOf("review", "provider", "");
    }

    /** 审查用的模型（空 = 跟主 Agent 一样） */
    public String model() {
        return settings.stringOf("review", "model", "");
    }

    /** 需要审查的工具名集合（用户没配过就用默认那批危险的） */
    public Set<String> reviewedTools() {
        Object raw = settings.section("review").get("tools");
        if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) {
            return DEFAULT_REVIEW_TOOLS;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o != null && !String.valueOf(o).isBlank()) {
                out.add(String.valueOf(o).trim());
            }
        }
        return out.isEmpty() ? DEFAULT_REVIEW_TOOLS : out;
    }

    /** 插件是不是开着 */
    public boolean isActive() {
        return settings.isEnabled(this);
    }

    // ------------------------------------------------------------------
    // 判定骨架
    // ------------------------------------------------------------------

    /**
     * 审查结论。
     *
     * @param needsReview 这次调用要不要送去审查
     * @param toolName    被审的工具名
     * @param provider    审查用提供商（空 = 沿用主 Agent）
     * @param model       审查用模型（空 = 沿用主 Agent）
     * @param reason      为什么需要/不需要审查（写日志与界面提示用）
     */
    public record ReviewDecision(boolean needsReview, String toolName, String provider,
                                 String model, String reason) {}

    /**
     * 这次工具调用要不要拦截审查。
     *
     * <p>【接线点】Lead 在 AgentLoop 里、真正执行工具**之前**调它（就在
     * {@code runToolWithTimeout} 前面）。判定为 true 时：
     * 用 {@code decision.provider()/model()} 建一次对话，
     * 把 {@link #buildReviewPrompt} 的结果发过去，拿 {@link #parseVerdict} 解析；
     * DENY 时**不要抛异常中断整个任务**，而是把 {@link #denyMessage} 当成工具结果回给模型
     * —— 这样模型知道"这一步被拦了、原因是这个"，还能换个安全做法继续。</p>
     *
     * <p>为什么工具名匹配用"小写精确"而不是包含：包含匹配会让 {@code git_commit}
     * 命中 {@code git_commit_amend} 这类无关名字，误审和漏审都是从这种"差不多"开始的。</p>
     */
    public ReviewDecision check(String sessionId, String toolName, Map<String, Object> arguments) {
        if (!isActive()) {
            return new ReviewDecision(false, toolName, provider(), model(), "插件未开启");
        }
        if (toolName == null || toolName.isBlank()) {
            return new ReviewDecision(false, toolName, provider(), model(), "工具名为空");
        }
        Set<String> tools = reviewedTools();
        boolean hit = tools.stream().anyMatch(t -> t.equalsIgnoreCase(toolName.trim()));
        if (!hit) {
            return new ReviewDecision(false, toolName, provider(), model(),
                "不在审查清单里（当前审查 " + tools.size() + " 个工具）");
        }
        return new ReviewDecision(true, toolName.trim(), provider(), model(),
            "命中审查清单，需要另一个模型审核");
    }

    /**
     * 给审查模型看的提示词。
     *
     * <p>写死的三条要求（必须回 ALLOW/DENY、只回一行、不确定就 DENY）是有意为之：
     * 审查调用是要被程序解析的，模型回一段散文就等于这次审查作废。</p>
     */
    public String buildReviewPrompt(String toolName, Map<String, Object> arguments,
                                    String workspacePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 LionBox 的工具调用安全审查员。判断下面这次工具调用该不该放行。\n");
        sb.append("只回一行，格式必须是：ALLOW 或 DENY: 一句话理由。不要解释、不要加别的字。\n");
        sb.append("判断标准：只读/可逆/在工作区内 = ALLOW；删数据、覆盖文件、动远端仓库、")
          .append("执行破坏性命令（格式化、递归删除、改系统设置）、访问工作区外路径 = DENY。\n");
        sb.append("拿不准就 DENY。\n\n");
        sb.append("工具：").append(toolName).append('\n');
        if (workspacePath != null && !workspacePath.isBlank()) {
            sb.append("工作区：").append(workspacePath).append('\n');
        }
        sb.append("参数：").append(arguments == null ? "{}" : arguments.toString()).append('\n');
        return sb.toString();
    }

    /**
     * 解析审查模型的回答。
     *
     * <p>【认不出来时放行，不是拦下】这是刻意的：审查插件是"额外的安全网"，
     * 真正的主闸是审批策略。如果审查模型抽风（回了半句、回了中文、什么都没回），
     * 这里拦下来会让用户"什么都没干成、还不知道为什么" —— 比漏放一次更糟。
     * 所以认不出来 = ALLOW，但理由里写明"未能解析，已按放行处理"，让日志查得出来。</p>
     */
    public static Verdict parseVerdict(String modelReply) {
        if (modelReply == null || modelReply.isBlank()) {
            return new Verdict(true, "审查模型没有返回内容，按放行处理");
        }
        String text = modelReply.trim();
        String upper = text.toUpperCase();
        if (upper.startsWith("DENY") || upper.contains("DENY")) {
            int idx = upper.indexOf("DENY");
            String reason = text.substring(idx + 4).replaceFirst("^[\\s:：,，-]+", "").trim();
            return new Verdict(false, reason.isEmpty() ? "审查模型判定为拒绝（未给理由）" : reason);
        }
        if (upper.contains("ALLOW")) {
            return new Verdict(true, "审查模型放行");
        }
        return new Verdict(true, "未能解析审查结果（原文：" + shorten(text) + "），按放行处理");
    }

    /**
     * 拦截时回给模型的话（工具结果）。
     *
     * <p>必须写清三件事：被拦了、为什么、下一步怎么办。只写"被拒绝"的话，
     * 模型会原样再试一次 —— 而它每试一次就多烧一轮。
     */
    public String denyMessage(String toolName, String reason) {
        return "这次调用被自动授权审查拦下了（工具：" + toolName + "）。\n"
            + "审查意见：" + reason + "\n"
            + "请改用不会造成破坏的做法：把破坏性操作改成只读查看、把删除改成先备份再确认、"
            + "或者向用户说明你为什么需要这个操作，让用户来决定。\n"
            + "（用户可在设置 → 插件 → 自动授权审查里调整审查用的模型或关掉这个插件。）";
    }

    /**
     * 放行判定。
     *
     * @param allow  true = 放行，false = 拦截
     * @param reason 理由（会进日志，拦截时还会回给模型）
     */
    public record Verdict(boolean allow, String reason) {}

    private static String shorten(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    // TODO(Lead 接线)：AgentLoop 里执行工具前插一段（大约在 runToolWithTimeout 调用点）：
    //   ReviewDecision d = approvalReviewPlugin.check(sessionId, toolName, args);
    //   if (d.needsReview()) {
    //       String reply = <用 d.provider()/d.model() 发一次对话，内容 =
    //                       plugin.buildReviewPrompt(toolName, args, workspacePath)>;
    //       Verdict v = ApprovalReviewPlugin.parseVerdict(reply);
    //       if (!v.allow()) {
    //           ToolResult denied = ToolResult.error(plugin.denyMessage(toolName, v.reason()));
    //           // 当作工具结果回给模型，继续循环 —— 不要 return 掉整条消息
    //       }
    //   }
    // 注意：审查对话要计进用量统计、也要能被"停止"按钮打断（否则一个卡住的审查
    // 会让整个任务僵在那里）。
}
