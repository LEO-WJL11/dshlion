package com.lioncode.core.plugin.team;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginKind;
import com.lioncode.core.plugin.PluginSettings;
import org.springframework.stereotype.Component;

/**
 * 子智能体插件：把子任务派给"另一个自己"去干，并给这件事套上三条缰绳。
 *
 * <p>职责划分：本类只管"能不能派、派几个、用哪个模型"（{@link #checkSpawn}、
 * {@link #checkConcurrency}、{@link #config}）；**真正派活的是
 * {@link SubAgentTool}（工具名 agent_spawn）** —— 它由 {@link TeamToolRegistrar} 注册进
 * 插件注册表，模型一轮里调它，它就在一条同步链路上起一个独立会话跑子 Agent，
 * 跑完把结论当工具结果带回主 Agent。端到端回归见
 * {@code tools/checks/_check_plugin_extras.py} 的第 1–3 项。</p>
 *
 * <p>为什么不在这里直接起线程去跑子智能体：AgentLoop 是按会话串行推进的，
 * 插件自己起线程会让"这个会话现在有几件事在跑"变得没人说得清（谁的上下文、谁的审批、
 * 谁的工作区？）。所以派发走的是主循环自己的工具调用链路，串行、可停、可审计。</p>
 */
@Component
public class SubAgentPlugin implements Plugin {

    public static final String PLUGIN_ID = "plugin.subagent";

    private final PluginSettings settings;

    public SubAgentPlugin(PluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "subagent";
    }

    @Override
    public String getDisplayName() {
        return "子智能体";
    }

    @Override
    public String getDescription() {
        return "把子任务派给子智能体执行：可限制递归层级、并发数量，并指定它用哪个模型";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.SUBAGENT;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 当前配置（每次现读，用户在设置里改完立刻生效） */
    public SubAgentConfig config() {
        int depth = settings.intOf("subagent", "maxDepth",
            PluginSettings.DEFAULT_SUBAGENT_MAX_DEPTH);
        int concurrency = settings.intOf("subagent", "maxConcurrency",
            PluginSettings.DEFAULT_SUBAGENT_MAX_CONCURRENCY);
        return new SubAgentConfig(
            settings.isEnabled(this),
            Math.max(depth, 0),
            Math.max(concurrency, 1),
            settings.stringOf("subagent", "provider", ""),
            settings.stringOf("subagent", "model", ""));
    }

    // ------------------------------------------------------------------
    // 强制校验（Lead 接线时直接调这两个）
    // ------------------------------------------------------------------

    /**
     * 这一层能不能派子智能体。
     *
     * <p>【接线点】Lead 在 AgentLoop 里做派发之前调它，语义是：
     * "当前这个 Agent 处在第 {@code depth} 层，它想派一个子智能体"。
     * 返回 null = 放行；返回字符串 = <b>明确拒绝的理由</b>，
     * 调用方应当把这个字符串当成工具结果回给模型（而不是抛异常），
     * 这样模型下一轮就知道"别再试了，是层级/开关的问题"，不会反复重试烧轮次。</p>
     *
     * @param depth 当前发起派发的 Agent 所在层级（主 Agent = 0）
     */
    public String checkSpawn(int depth) {
        SubAgentConfig cfg = config();
        if (!cfg.enabled()) {
            return "子智能体插件已被用户在设置里关闭，不能派发子智能体；"
                + "请自己完成任务，或在设置 → 插件里重新开启「子智能体」。";
        }
        if (depth < 0) {
            // 层级不该是负数；真出现了说明调用方传错了，直接拒绝比猜一个值安全
            return "派发子智能体失败：层级参数非法（depth=" + depth + "）。";
        }
        if (depth >= cfg.maxDepth()) {
            return "已达子智能体递归层级上限（当前第 " + depth + " 层，上限 "
                + cfg.maxDepth() + " 层）：这一层不能再派子智能体，请自己完成任务。"
                + "需要更深的层级，可在设置 → 插件 → 子智能体里调大「递归层级上限」。";
        }
        return null;
    }

    /**
     * 还能不能再开一个子智能体（并发上限）。
     *
     * @param running 现在已经在跑的子智能体个数
     * @return null = 放行；否则是给模型看的拒绝理由
     */
    public String checkConcurrency(int running) {
        SubAgentConfig cfg = config();
        if (!cfg.enabled()) {
            return "子智能体插件已被用户在设置里关闭，不能派发子智能体。";
        }
        if (!cfg.isConcurrencyAllowed(running)) {
            return "子智能体并发已达上限（正在跑 " + running + " 个，上限 "
                + cfg.maxConcurrency() + " 个）：先等它们出结果，或在设置里调大「并发数量上限」。";
        }
        return null;
    }

    /**
     * 子智能体这一层可用的层级号：主 Agent(0) 派出来的就是 1。
     * 放在插件里，免得调用方各自 +1 加错。
     */
    public int childDepth(int parentDepth) {
        return parentDepth + 1;
    }

    // TODO(Lead 接线)：在 AgentLoop 里接上"派发子智能体"的工具时，按这个顺序用：
    //   1) 先 parentDepth = 当前 Agent 层级（主循环 = 0），child = plugin.childDepth(parentDepth)
    //   2) String err = plugin.checkSpawn(child);  // 注意传的是**子**的层级
    //      err != null → 直接把 err 当作工具结果回给模型，别抛异常
    //   3) err = plugin.checkConcurrency(当前在跑的子智能体数)；同样处理
    //   4) 放行后：用 plugin.config().model()/provider()（空则沿用主 Agent）建一次子会话执行，
    //      子会话的 agent 层级设为 child，这样它再想派就会在第 2 步被拒。
}
