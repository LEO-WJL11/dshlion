package com.lioncode.model.adapter;

import java.util.List;

/**
 * 流式模型响应块
 */
public record ModelChunk(
    /** 增量内容 */
    String deltaContent,
    /** 增量工具调用 */
    List<ToolCallDelta> toolCallDeltas,
    /** 是否完成 */
    boolean finished,
    /** 完成原因 */
    String finishReason,
    /** 思考内容增量（thinking模式，DeepSeek等） */
    String reasoningContentDelta
) {
    /**
     * 工具调用增量
     */
    public record ToolCallDelta(
        /** 工具调用索引 */
        int index,
        /** 工具调用ID */
        String id,
        /** 工具名称增量 */
        String nameDelta,
        /** 工具参数增量 */
        String argumentsDelta
    ) {}
}
