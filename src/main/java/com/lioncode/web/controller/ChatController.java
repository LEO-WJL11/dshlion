package com.lioncode.web.controller;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.session.SessionManager;
import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.web.dto.ApiResponse;
import com.lioncode.web.dto.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 聊天控制器
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;
    private final AdapterManager adapterManager;

    public ChatController(AgentLoop agentLoop, SessionManager sessionManager, 
                          AdapterManager adapterManager) {
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
        this.adapterManager = adapterManager;
    }

    /**
     * 发送消息（同步模式）
     */
    @PostMapping
    public ApiResponse<String> sendMessage(@RequestBody ChatRequest request) {
        var sessionOpt = sessionManager.getSession(request.sessionId());
        if (sessionOpt.isEmpty()) {
            return ApiResponse.error("会话不存在: " + request.sessionId());
        }
        var session = sessionOpt.get();
        try {
            // 解析思考等级
            ThinkingLevel level = ThinkingLevel.MEDIUM;
            if (request.thinkingLevel() != null) {
                try {
                    level = ThinkingLevel.valueOf(request.thinkingLevel().toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 使用默认值
                }
            }

            // 获取模型名称
            String model = request.model();
            if (model == null || model.isBlank()) {
                model = "gpt-4o"; // 默认模型
            }

            // 调用Agent主循环
            String response = agentLoop.processMessage(
                request.sessionId(),
                request.message(),
                session.mode(),
                level,
                model
            );

            return ApiResponse.ok(response);
        } catch (Exception e) {
            return ApiResponse.error("处理消息失败: " + e.getMessage());
        }
    }

    /**
     * 发送消息（流式模式）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentLoop.AgentChunk> sendMessageStream(@RequestBody ChatRequest request) {
        var sessionOpt = sessionManager.getSession(request.sessionId());
        if (sessionOpt.isEmpty()) {
            return Flux.just(AgentLoop.AgentChunk.error("会话不存在"));
        }
        var session = sessionOpt.get();

        ThinkingLevel level = ThinkingLevel.MEDIUM;
        if (request.thinkingLevel() != null) {
            try {
                level = ThinkingLevel.valueOf(request.thinkingLevel().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        String model = request.model();
        if (model == null || model.isBlank()) {
            model = "gpt-4o";
        }

        return agentLoop.processMessageStream(
            request.sessionId(),
            request.message(),
            session.mode(),
            level,
            model
        );
    }

    /**
     * 切换模型适配器
     */
    @PostMapping("/adapter/switch")
    public ApiResponse<String> switchAdapter(@RequestBody SwitchAdapterRequest request) {
        try {
            var type = com.lioncode.model.adapter.ModelAdapter.AdapterType.valueOf(request.adapterType());
            boolean success = adapterManager.switchAdapter(type, request.sessionId());
            if (success) {
                return ApiResponse.ok("适配器已切换至: " + type);
            } else {
                return ApiResponse.error("适配器切换失败");
            }
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的适配器类型: " + request.adapterType());
        }
    }

    /**
     * 获取当前适配器状态
     */
    @GetMapping("/adapter/status")
    public ApiResponse<AdapterStatus> getAdapterStatus() {
        var active = adapterManager.getActiveAdapter();
        return ApiResponse.ok(new AdapterStatus(
            active.getName(),
            active.getType().name(),
            active.isAvailable()
        ));
    }

    /**
     * 更新适配器配置（baseUrl和apiKey）
     */
    @PostMapping("/adapter/config")
    public ApiResponse<String> updateAdapterConfig(@RequestBody AdapterConfigRequest request) {
        var active = adapterManager.getActiveAdapter();
        active.updateConfig(Map.of(
            "baseUrl", request.baseUrl() != null ? request.baseUrl() : "",
            "apiKey", request.apiKey() != null ? request.apiKey() : ""
        ));
        return ApiResponse.ok("适配器配置已更新", null);
    }

    /**
     * 切换适配器请求
     */
    public record SwitchAdapterRequest(String sessionId, String adapterType) {}

    /**
     * 适配器配置请求
     */
    public record AdapterConfigRequest(String baseUrl, String apiKey) {}

    /**
     * 适配器状态DTO
     */
    public record AdapterStatus(String name, String type, boolean available) {}
}
