package com.lioncode.model.adapter;

import java.util.List;

/**
 * 模型信息
 * 
 * 包含模型的完整能力信息，包括支持的思考等级
 */
public record ModelInfo(
    /** 模型ID */
    String id,
    /** 模型显示名称 */
    String name,
    /** 模型所有者/提供商 */
    String owner,
    /** 是否支持思考等级 */
    boolean supportsThinking,
    /** 是否支持工具调用 */
    boolean supportsToolCalls,
    /** 模型来源类型 */
    ModelSource source,
    /** TPM限制（Token-Plan时） */
    Long tpmLimit,
    /** RPM限制（Token-Plan时） */
    Long rpmLimit,
    /** 支持的思考等级列表 */
    List<ThinkingLevelOption> thinkingLevels,
    /** 最大上下文窗口（token数） */
    Integer maxContextTokens,
    /** 最大输出token数 */
    Integer maxOutputTokens
) {
    /**
     * 模型来源枚举
     */
    public enum ModelSource {
        /** 云端API */
        CLOUD_API,
        /** Token-Plan订阅 */
        TOKEN_PLAN,
        /** 本地GGUF */
        LOCAL_GGUF
    }

    /**
     * 思考等级选项
     */
    public record ThinkingLevelOption(
        /** 等级代码 */
        String code,
        /** 显示名称 */
        String displayName,
        /** 描述 */
        String description,
        /** token预算 */
        int tokenBudget,
        /** 是否为默认选项 */
        boolean isDefault
    ) {}

    /**
     * 获取默认思考等级
     */
    public ThinkingLevelOption getDefaultThinkingLevel() {
        if (thinkingLevels == null || thinkingLevels.isEmpty()) {
            return null;
        }
        return thinkingLevels.stream()
            .filter(ThinkingLevelOption::isDefault)
            .findFirst()
            .orElse(thinkingLevels.get(0));
    }

    /**
     * 创建简化的模型信息（无思考等级详情）
     */
    public static ModelInfo simple(String id, String name, String owner, 
                                    boolean supportsThinking, boolean supportsToolCalls,
                                    ModelSource source) {
        return new ModelInfo(id, name, owner, supportsThinking, supportsToolCalls, 
            source, null, null, List.of(), null, null);
    }
}
