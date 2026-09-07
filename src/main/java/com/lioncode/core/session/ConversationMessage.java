package com.lioncode.core.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 对话消息模型
 * 
 * 完整记录对话中的每一条消息，包括：
 * - 角色（用户、助手、系统、工具）
 * - 消息内容
 * - 工具调用信息
 * - 时间戳和元数据
 */
public record ConversationMessage(
    /** 消息唯一ID */
    @JsonProperty("messageId") String messageId,
    /** 所属会话ID */
    @JsonProperty("sessionId") String sessionId,
    /** 角色：user, assistant, system, tool */
    @JsonProperty("role") String role,
    /** 消息内容 */
    @JsonProperty("content") String content,
    /** 思考内容（DeepSeek等thinking模式要求原样回传） */
    @JsonProperty("reasoningContent") String reasoningContent,
    /** 工具调用列表（assistant消息时有值） */
    @JsonProperty("toolCalls") List<ToolCallRecord> toolCalls,
    /** 工具调用ID（tool角色消息时有值） */
    @JsonProperty("toolCallId") String toolCallId,
    /** 工具名称（tool角色消息时有值） */
    @JsonProperty("toolName") String toolName,
    /** 时间戳 */
    @JsonProperty("timestamp") Instant timestamp,
    /** 额外元数据 */
    @JsonProperty("metadata") Map<String, Object> metadata
) {
    /**
     * 创建用户消息
     */
    public static ConversationMessage user(String sessionId, String content) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "user", content, null, null, null, null,
            Instant.now(), Map.of()
        );
    }

    /**
     * 创建助手消息
     */
    public static ConversationMessage assistant(String sessionId, String content) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "assistant", content, null, null, null, null,
            Instant.now(), Map.of()
        );
    }

    /**
     * 创建助手消息（带思考内容）
     */
    public static ConversationMessage assistant(String sessionId, String content, String reasoningContent) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "assistant", content, reasoningContent, null, null, null,
            Instant.now(), Map.of()
        );
    }

    /**
     * 创建助手消息（带工具调用）
     */
    public static ConversationMessage assistantWithToolCalls(String sessionId, String content, 
                                                              List<ToolCallRecord> toolCalls) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "assistant", content, null, toolCalls, null, null,
            Instant.now(), Map.of()
        );
    }

    /**
     * 创建助手消息（带工具调用和思考内容）
     */
    public static ConversationMessage assistantWithToolCalls(String sessionId, String content,
                                                              List<ToolCallRecord> toolCalls,
                                                              String reasoningContent) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "assistant", content, reasoningContent, toolCalls, null, null,
            Instant.now(), Map.of()
        );
    }

    /**
     * 创建系统消息
     */
    public static ConversationMessage system(String sessionId, String content) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "system", content, null, null, null, null,
            Instant.now(), Map.of()
        );
    }

    /**
     * 创建工具结果消息
     */
    public static ConversationMessage toolResult(String sessionId, String toolCallId, 
                                                  String toolName, String content) {
        return new ConversationMessage(
            java.util.UUID.randomUUID().toString(),
            sessionId, "tool", content, null, null, toolCallId, toolName,
            Instant.now(), Map.of()
        );
    }

    /**
     * 工具调用记录
     */
    public record ToolCallRecord(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("arguments") Map<String, Object> arguments
    ) {}
}
