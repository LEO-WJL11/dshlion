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
    String reasoningContent,
    /**
     * 模型**想做**工具调用、但调用是残缺的（缺 name，或 arguments 不是合法 JSON），
     * 这类调用已被丢弃、不可能被执行。
     *
     * 有它 AgentLoop 才能区分下面两种情况，否则都表现为「没有工具调用、正文为空」：
     *   - 模型正常收尾（内容为空属于罕见但合法）
     *   - 模型想调工具却吐了残缺调用（实测某些服务商在提示词里看到文本格式示例时就会这样）
     * 后者要纠正重试，而不是把任务空着结束。
     */
    boolean malformedToolCall
) {
    /**
     * 兼容旧调用点：默认没有残缺工具调用
     */
    public ModelResponse(String content, List<ChatMessage.ToolCall> toolCalls, boolean finished,
                         TokenUsage usage, String finishReason, String reasoningContent) {
        this(content, toolCalls, finished, usage, finishReason, reasoningContent, false);
    }

    /**
     * Token使用统计
     */
    public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
    ) {}
}
