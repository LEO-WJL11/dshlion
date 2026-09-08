package com.lioncode.web.controller;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.session.SessionManager;
import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.config.AppConfigStore;
import com.lioncode.web.dto.ApiResponse;
import com.lioncode.web.dto.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 聊天控制器
 * 
 * 适配器配置、模型选择等用户配置均持久化到磁盘（AppConfigStore），
 * 应用重启后自动恢复。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;
    private final AdapterManager adapterManager;
    private final AppConfigStore configStore;

    public ChatController(AgentLoop agentLoop, SessionManager sessionManager, 
                          AdapterManager adapterManager, AppConfigStore configStore) {
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
        this.adapterManager = adapterManager;
        this.configStore = configStore;
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

            // 获取模型名称：请求优先，其次持久化配置，最后默认
            String model = request.model();
            if (model == null || model.isBlank()) {
                model = getSavedModel(adapterManager.getActiveAdapter());
            }

            // 调用Agent主循环（使用会话当前生效模式）
            String response = agentLoop.processMessage(
                request.sessionId(),
                request.message(),
                sessionManager.getEffectiveMode(request.sessionId()),
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
            model = getSavedModel(adapterManager.getActiveAdapter());
        }

        return agentLoop.processMessageStream(
            request.sessionId(),
            request.message(),
            sessionManager.getEffectiveMode(request.sessionId()),
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
            var type = ModelAdapter.AdapterType.valueOf(request.adapterType());
            boolean success = adapterManager.switchAdapter(type, request.sessionId());
            if (success) {
                // 持久化激活适配器
                configStore.set("activeAdapter", type.name());
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
     * 更新适配器配置（baseUrl和apiKey），并持久化
     */
    @PostMapping("/adapter/config")
    public ApiResponse<String> updateAdapterConfig(@RequestBody AdapterConfigRequest request) {
        // 目标适配器：请求指定，否则当前激活的
        ModelAdapter target = adapterManager.getActiveAdapter();
        ModelAdapter.AdapterType targetType = target.getType();
        if (request.adapterType() != null && !request.adapterType().isBlank()) {
            try {
                targetType = ModelAdapter.AdapterType.valueOf(request.adapterType());
                target = adapterManager.getAdapter(targetType).orElse(target);
            } catch (IllegalArgumentException ignored) {}
        }

        Map<String, Object> cfg = new HashMap<>();
        if (request.baseUrl() != null) cfg.put("baseUrl", request.baseUrl());
        if (request.apiKey() != null) cfg.put("apiKey", request.apiKey());
        target.updateConfig(cfg);

        // 持久化该适配器的配置
        Map<String, Object> saved = new HashMap<>(configStore.getMap(adapterKey(targetType)));
        saved.putAll(cfg);
        if (request.model() != null && !request.model().isBlank()) {
            saved.put("model", request.model());
        }
        if (request.thinkingLevel() != null && !request.thinkingLevel().isBlank()) {
            saved.put("thinkingLevel", request.thinkingLevel());
        }
        configStore.set(adapterKey(targetType), saved);

        // 将目标适配器设为激活（跳过可用性检查，配置保存即意图使用）
        adapterManager.restoreActiveAdapter(targetType);
        configStore.set("activeAdapter", targetType.name());

        return ApiResponse.ok("适配器配置已保存", null);
    }

    /**
     * 保存当前选中的模型与思考等级
     */
    @PostMapping("/adapter/model")
    public ApiResponse<String> saveModelSelection(@RequestBody ModelSelectionRequest request) {
        ModelAdapter.AdapterType type = adapterManager.getActiveAdapter().getType();
        Map<String, Object> saved = new HashMap<>(configStore.getMap(adapterKey(type)));
        if (request.model() != null) {
            saved.put("model", request.model());
        }
        if (request.thinkingLevel() != null) {
            saved.put("thinkingLevel", request.thinkingLevel());
        }
        configStore.set(adapterKey(type), saved);
        return ApiResponse.ok("模型选择已保存", null);
    }

    /**
     * 获取完整应用配置快照（前端初始化时恢复状态）
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        return ApiResponse.ok(configStore.snapshot());
    }

    private String adapterKey(ModelAdapter.AdapterType type) {
        return switch (type) {
            case OPENAI_COMPATIBLE -> "openai";
            case ANTHROPIC -> "anthropic";
        };
    }

    private String getSavedModel(ModelAdapter adapter) {
        Map<String, Object> saved = configStore.getMap(adapterKey(adapter.getType()));
        Object model = saved.get("model");
        if (model instanceof String s && !s.isBlank()) {
            return s;
        }
        return "gpt-4o"; // 默认模型
    }

    /**
     * 切换适配器请求
     */
    public record SwitchAdapterRequest(String sessionId, String adapterType) {}

    /**
     * 适配器配置请求
     */
    public record AdapterConfigRequest(String baseUrl, String apiKey, String adapterType,
                                       String model, String thinkingLevel) {}

    /**
     * 模型选择请求
     */
    public record ModelSelectionRequest(String model, String thinkingLevel) {}

    /**
     * 适配器状态DTO
     */
    public record AdapterStatus(String name, String type, boolean available) {}
}
