package com.lioncode.model.adapter;

import java.util.List;

/**
 * 模型响应
 */
public record ModelResponse(
    /** 响应内容 */
    String content,
    /** 工具调用列表 */
    List<ChatMessage.ToolCall> toolCalls,
    /** 是否完成 */
    boolean finished,
    /** 使用的token数 */
    TokenUsage usage,
    /** 完成原因 */
    String finishReason,
    /** 思考内容（DeepSeek等thinking模式要求原样回传，普通模型为null） */
    String reasoningContent
) {
    /**
     * Token使用统计
     */
    public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
    ) {}
}
