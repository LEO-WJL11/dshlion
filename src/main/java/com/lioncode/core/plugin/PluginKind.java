package com.lioncode.core.plugin;

import java.util.Arrays;

/**
 * 插件分类（"一切皆插件"里的"类"）。
 *
 * <p>【为什么要在 {@link Plugin.PluginType} 之外再加一层】
 * {@code PluginType} 只有 SKILL / TOOL 两种，是给代码看的（决定走哪条执行路径）。
 * 但用户在设置里要看到的是"这是基础工具还是进阶工具""这是终端插件还是自动化任务插件"——
 * 那是给人看的分类。两者一一对应会很难受：60 个工具类就得各自写死自己是哪一类。
 * 所以这里做成"派生"的：工具类按"极简模式能不能用"自动分成基础/进阶，
 * 技能类归 SKILL，其余六类由各自的系统插件显式声明。</p>
 *
 * <p>加分类只影响"展示 + 开关粒度"，不影响任何执行路径 ——
 * 也就是说新加一类不需要动 AgentLoop。</p>
 */
public enum PluginKind {

    /** 基础工具：极简模式下就开放的那些（文件读写、终端） */
    BASE_TOOL("基础工具", "极简模式就能用的工具（文件读写/终端这类「没它干不了活」的）"),

    /** 进阶工具：只有标准模式才开放（网络、Git、编解码…） */
    ADVANCED_TOOL("进阶工具", "标准模式下才开放的工具（网络/Git/编解码等）"),

    /** 技能：给模型注入领域提示词的能力包 */
    SKILL("技能", "向系统提示词注入领域经验的技能包"),

    /** 子智能体：把任务派给"另一个自己"，这里管递归层级/并发/模型 */
    SUBAGENT("子智能体", "把子任务派给子智能体执行，可限制递归层级、并发数与所用模型"),

    /** 终端：常驻 PowerShell 的行为约束（每条命令跑多久、最多吐多少字） */
    TERMINAL("终端", "常驻终端插件：限制每条命令最长运行时间与最大输出"),

    /** Agent 大循环：主循环参数（最大轮次/工具超时/空转容忍） */
    AGENT_LOOP("Agent 大循环", "控制 Agent 派发工具调用的方式（轮次上限、工具超时、空转容忍）"),

    /** 智能体团队：用户自定义的一组智能体（各自模式与职责） */
    AGENT_TEAM("智能体团队", "一组用户自定义的智能体：每个用什么模式、负责干什么"),

    /** 自动授权审查：让另一个模型来审这次工具调用该不该放行 */
    APPROVAL_REVIEW("自动授权审查", "用另一个模型对话审核危险的工具调用，决定是否拦截"),

    /** 自动化任务：按时间/周期在指定会话里自动干活 */
    AUTOMATION("自动化任务", "按设定时间或周期，在指定会话里自动执行任务");

    private final String displayName;
    private final String description;

    PluginKind(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /** 中文显示名（设置面板直接显示这个） */
    public String getDisplayName() {
        return displayName;
    }

    /** 一句话说明这类插件是干什么的 */
    public String getDescription() {
        return description;
    }

    /**
     * 宽容解析分类名。
     *
     * <p>为什么宽容：分类会从 JSON / 命令行 / REST 参数进来（scaffold 的 {@code kind} 字段），
     * 用户手写 {@code base_tool}、{@code 基础工具}、{@code BaseTool} 都是合理的，
     * 一律报 400 只会让人反复试。认不出来返回 null，由调用方决定兜底成什么。
     */
    public static PluginKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String want = raw.trim();
        return Arrays.stream(values())
            .filter(k -> k.name().equalsIgnoreCase(want)
                      || k.getDisplayName().equals(want)
                      || k.name().replace("_", "").equalsIgnoreCase(want.replace("_", "").replace("-", "")))
            .findFirst()
            .orElse(null);
    }
}
