package com.lioncode.model.adapter;

import java.util.List;
import java.util.Map;

/**
 * 聊天消息
 */
public record ChatMessage(
    /** 角色：system, user, assistant, tool */
    String role,
    /** 消息内容 */
    String content,
    /** 工具调用列表（assistant消息时） */
    List<ToolCall> toolCalls,
    /** 工具调用ID（tool消息时） */
    String toolCallId,
    /** 消息名称（可选） */
    String name,
    /** 思考内容（DeepSeek等thinking模式要求原样回传，普通模型为null） */
    String reasoningContent
) {
    /**
     * 创建系统消息
     */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, null, null);
    }

    /**
     * 创建用户消息
     */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, null, null);
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null, null, null);
    }

    /**
     * 创建助手消息（带思考内容，供thinking模式多轮回传）
     */
    public static ChatMessage assistant(String content, String reasoningContent) {
        return new ChatMessage("assistant", content, null, null, null, reasoningContent);
    }

    /**
     * 创建工具结果消息
     */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId, null, null);
    }

    /**
     * 创建带工具调用的助手消息
     */
    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null, null, null);
    }

    /**
     * 创建带工具调用的助手消息（带思考内容，供thinking模式多轮回传）
     */
    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls,
                                                      String reasoningContent) {
        return new ChatMessage("assistant", content, toolCalls, null, null, reasoningContent);
    }

    /**
     * 工具调用记录
     */
    public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments
    ) {}
}
