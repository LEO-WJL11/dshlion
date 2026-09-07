package com.lioncode.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Lion-Code事件记录
 * 
 * 事件溯源的核心数据结构，记录Agent运行时的所有关键动作。
 * 支持JSON序列化/反序列化，用于持久化存储和回放。
 */
public record LionEvent(
    /** 事件唯一ID */
    @JsonProperty("eventId") String eventId,
    /** 所属会话ID */
    @JsonProperty("sessionId") String sessionId,
    /** 事件类型 */
    @JsonProperty("type") EventType type,
    /** 事件时间戳 */
    @JsonProperty("timestamp") Instant timestamp,
    /** 事件数据（JSON可序列化） */
    @JsonProperty("data") Map<String, Object> data,
    /** 事件摘要（人类可读） */
    @JsonProperty("summary") String summary
) {
    @JsonCreator
    public LionEvent(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("type") EventType type,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("summary") String summary
    ) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.type = type;
        this.timestamp = timestamp;
        this.data = data != null ? data : Map.of();
        this.summary = summary;
    }

    /**
     * 事件类型枚举
     */
    public enum EventType {
        /** 用户消息 */
        USER_MESSAGE("用户消息"),
        /** 模型思考 */
        MODEL_THINKING("模型思考"),
        /** 模型响应 */
        MODEL_RESPONSE("模型响应"),
        /** 工具调用开始 */
        TOOL_CALL_START("工具调用开始"),
        /** 工具调用完成 */
        TOOL_CALL_COMPLETE("工具调用完成"),
        /** 工具调用失败 */
        TOOL_CALL_ERROR("工具调用失败"),
        /** 会话创建 */
        SESSION_CREATED("会话创建"),
        /** 会话销毁 */
        SESSION_DESTROYED("会话销毁"),
        /** 插件加载 */
        PLUGIN_LOADED("插件加载"),
        /** 插件卸载 */
        PLUGIN_UNLOADED("插件卸载"),
        /** 适配器切换 */
        ADAPTER_SWITCH("适配器切换"),
        /** 工作区切换 */
        WORKSPACE_CHANGE("工作区切换"),
        /** 系统错误 */
        SYSTEM_ERROR("系统错误");

        private final String displayName;

        EventType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
