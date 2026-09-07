package com.lioncode.core.agent;

/**
 * 模型思考等级
 * 
 * 云端API后端可用，可下发思考等级参数；
 * 本地GGUF服务（通过OpenAI兼容协议接入）不传递思考等级参数。
 */
public enum ThinkingLevel {

    /** 低思考等级：快速响应 */
    LOW("low", "低", 1024),

    /** 中思考等级：平衡模式 */
    MEDIUM("medium", "中", 4096),

    /** 高思考等级：深度推理 */
    HIGH("high", "高", 16384),

    /** 最高思考等级：复杂任务 */
    MAX("max", "最高", 32768);

    private final String code;
    private final String displayName;
    private final int tokenBudget;

    ThinkingLevel(String code, String displayName, int tokenBudget) {
        this.code = code;
        this.displayName = displayName;
        this.tokenBudget = tokenBudget;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public int getTokenBudget() { return tokenBudget; }
}
