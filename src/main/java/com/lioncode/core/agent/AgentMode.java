package com.lioncode.core.agent;

import java.util.List;

/**
 * Agent 工作模式。
 *
 * <p>**只保留两种**（用户要求：只留标准和极简）：
 * <ul>
 *   <li>{@link #STANDARD} 标准模式：全部工具开放（默认）</li>
 *   <li>{@link #MINIMAL} 极简模式：只开放文件、Shell 工具</li>
 * </ul>
 *
 * <p>PTC 和 CREATIVE **不再开放给用户**。枚举常量故意留着不删：磁盘上的老会话
 * （{@code ~/.lioncode/sessions}）里可能存着 {@code mode=PTC} / {@code CREATIVE}，
 * 删掉枚举值会让 Jackson 反序列化直接失败、那些会话就加载不出来了。
 * 它们统一由 {@link #normalize(AgentMode)} 归一到 STANDARD。
 */
public enum AgentMode {

    /**
     * PTC 模式：预规划后执行。
     *
     * @deprecated 不再开放给用户，老数据兼容用；会被 {@link #normalize(AgentMode)} 归一成 STANDARD
     */
    PTC("PTC", "预规划模式", "模型预先完整规划全部工具调用步骤再执行"),

    /**
     * 创造模式：可管理插件。
     *
     * @deprecated 不再开放给用户，老数据兼容用；会被 {@link #normalize(AgentMode)} 归一成 STANDARD
     */
    CREATIVE("CREATIVE", "创造模式", "AI可以编写、修改、安装、卸载插件"),

    /** 标准模式：全部工具开放 */
    STANDARD("STANDARD", "标准模式", "全部工具开放"),

    /** 极简模式：仅基础工具 */
    MINIMAL("MINIMAL", "极简模式", "仅开放文件、Shell工具");

    private final String code;
    private final String displayName;
    private final String description;

    AgentMode(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /** 界面上能选的模式 —— **就这两个** */
    public static final List<AgentMode> SELECTABLE = List.of(STANDARD, MINIMAL);

    /**
     * 把不再开放的模式归一成标准模式；其余原样返回（null 也当归一）。
     *
     * <p>所有"拿到一个模式"的地方都该过这一道，否则老会话的 PTC/CREATIVE
     * 会绕过界面继续生效，用户就会发现"我明明只剩两个模式，怎么还有个预规划"。
     */
    public static AgentMode normalize(AgentMode mode) {
        if (mode == null || mode == PTC || mode == CREATIVE) {
            return STANDARD;
        }
        return mode;
    }

    /**
     * 按名字解析（大小写不敏感）。
     *
     * <p>空/null 当标准模式；不认识的名字抛 {@link IllegalArgumentException}，由调用方报给用户。
     * PTC / CREATIVE 能解析成功，但会被归一成 STANDARD（老前端/老会话不会一下子坏掉）。
     */
    public static AgentMode fromName(String name) {
        if (name == null || name.isBlank()) {
            return STANDARD;
        }
        return normalize(valueOf(name.trim().toUpperCase()));
    }
}
