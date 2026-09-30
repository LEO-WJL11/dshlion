package com.lioncode.core.plugin.team;

/**
 * 子智能体的运行约束（"递归层级上限 / 并发数量上限 / 用哪个模型"）。
 *
 * @param enabled        插件是否开启（关掉 = 不允许派子智能体）
 * @param maxDepth       递归层级上限。0 = 只允许主 Agent 自己干；1 = 主 Agent 可以派子智能体，
 *                       但子智能体不能再往下派；2 = 允许"子智能体再派子智能体"。
 * @param maxConcurrency 同时最多几个子智能体在跑（防止一次派 20 个把本机模型挤爆）
 * @param provider       子智能体用哪个提供商（空 = 跟主 Agent 一样）
 * @param model          子智能体用哪个模型（空 = 跟主 Agent 一样）
 */
public record SubAgentConfig(
    boolean enabled,
    int maxDepth,
    int maxConcurrency,
    String provider,
    String model
) {

    /**
     * 这个层级的子智能体允不允许存在。
     *
     * <p>层级定义：主 Agent = 0 层，主 Agent 派出来的子智能体 = 1 层。
     * 所以 {@code maxDepth=1} 表示"只允许派一层"。</p>
     */
    public boolean isDepthAllowed(int depth) {
        return enabled && depth >= 0 && depth <= maxDepth;
    }

    /** 并发是否还有余量 */
    public boolean isConcurrencyAllowed(int running) {
        return enabled && running < maxConcurrency;
    }

    /** 用的模型描述（给日志/提示词用） */
    public String modelLabel() {
        if (model == null || model.isBlank()) {
            return "（跟主 Agent 相同）";
        }
        if (provider == null || provider.isBlank()) {
            return model;
        }
        return provider + " / " + model;
    }
}
