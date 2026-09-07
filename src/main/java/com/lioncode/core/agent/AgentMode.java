package com.lioncode.core.agent;

/**
 * Agent工作模式枚举
 * 
 * 四种工作模式：
 * - PTC：模型预先规划全部工具调用步骤再执行
 * - CREATIVE：创造模式，AI可编写、修改、安装、卸载插件
 * - STANDARD：标准模式，全部工具开放
 * - MINIMAL：极简模式，仅开放文件、Shell工具
 */
public enum AgentMode {

    /** PTC模式：预规划后执行 */
    PTC("PTC", "预规划模式", "模型预先完整规划全部工具调用步骤再执行"),

    /** 创造模式：可管理插件 */
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
}
