package com.lioncode.web.controller;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.context.MentionResolver;
import com.lioncode.core.event.EventStore;
import com.lioncode.core.event.LionEvent;
import com.lioncode.core.session.SessionManager;
import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.config.AppConfigStore;
import com.lioncode.queue.SessionDispatcher;
import com.lioncode.web.dto.ApiResponse;
import com.lioncode.web.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 聊天控制器
 * 
 * - 同步接口经SessionDispatcher队列串行处理（同会话并发安全 + Steer插队）
 * - 流式接口与队列互斥（会话忙时拒绝）
 * - 适配器配置、模型选择等用户配置均持久化到磁盘（AppConfigStore）
 * - 消息里的 @ 引用（@file / @history / @skill）在这里**发出去之前**展开成真实上下文
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;
    private final AdapterManager adapterManager;
    private final AppConfigStore configStore;
    private final SessionDispatcher dispatcher;
    private final com.lioncode.core.session.SessionTitleService titleService;
    private final MentionResolver mentionResolver;
    private final EventStore eventStore;

    public ChatController(AgentLoop agentLoop, SessionManager sessionManager,
                          AdapterManager adapterManager, AppConfigStore configStore,
                          SessionDispatcher dispatcher,
                          com.lioncode.core.session.SessionTitleService titleService,
                          MentionResolver mentionResolver, EventStore eventStore) {
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
        this.adapterManager = adapterManager;
        this.configStore = configStore;
        this.dispatcher = dispatcher;
        this.titleService = titleService;
        this.mentionResolver = mentionResolver;
        this.eventStore = eventStore;
    }

    /**
     * 发送消息（同步模式，经队列串行处理）
     */
    @PostMapping
    public ApiResponse<String> sendMessage(@RequestBody ChatRequest request) {
        // 【为什么先做空值校验】sessionId 为 null 时 sessionManager.getSession(null) 会在内部的
        // ConcurrentHashMap 里抛 NPE（ConcurrentHashMap 不收 null 键），前端收到的是 500 而不是
        // 一句能看懂的错。端到端压测就是这么撞出来的：客户端少传一个字段 → 500 → 用户完全不知道
        // 自己错在哪。
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error("缺少 sessionId（会话不存在）");
        }
        if (request.message() == null || request.message().isBlank()) {
            return ApiResponse.error("消息内容为空");
        }
        if (sessionManager.getSession(request.sessionId()).isEmpty()) {
            return ApiResponse.error("会话不存在: " + request.sessionId());
        }
        try {
            ThinkingLevel level = parseLevel(request.thinkingLevel());
            String model = request.model();
            if (model == null || model.isBlank()) {
                model = getSavedModel(adapterManager.getActiveAdapter());
            }

            // 经调度器入队处理：同会话串行，Steer=普通消息（前端已用isSteer字段区分）
            boolean steer = Boolean.TRUE.equals(request.isSteer());

            // @ 引用展开：@file: 读文件、@history: 取历史会话、@skill: 取技能正文。
            // 【为什么展开放在入队之前】展开要读盘，入队之后就是"轮到我才做"了；
            // 而且早展开能保证事件流里"展开 → 用户消息 → 模型思考"的顺序是对的。
            String message = expandMentions(request.sessionId(), request.message());

            // 会话还没有名字的话，拿这条消息去让模型生成一个标题。
            // 异步执行、失败有兜底，不会拖慢这条消息本身的响应。
            // 用**用户原话**（没展开的）：`@file:src/xxx.java` 当标题比几千字文件内容合适得多。
            titleService.generateAsync(request.sessionId(), request.message());

            var future = dispatcher.submit(request.sessionId(), message,
                model, level, steer);

            // 【不设超时】原来这里是 future.get(10, TimeUnit.MINUTES)：本地模型 11 token/s，
            // 工具多的任务一超过 10 分钟就被砍成"处理超时"，前面干的活全白费
            // （用户实测跑到第 70 个工具时就是这么断的）。改成一直等，该跑多久跑多久；
            // 想中断用界面上的停止按钮（走 AgentControlManager）。
            String response = future.get();
            return ApiResponse.ok(response);
        } catch (Exception e) {
            return ApiResponse.error("处理消息失败: " + e.getMessage());
        }
    }

    /**
     * 发送消息（流式模式，与队列互斥）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentLoop.AgentChunk> sendMessageStream(@RequestBody ChatRequest request) {
        var sessionOpt = sessionManager.getSession(request.sessionId());
        if (sessionOpt.isEmpty()) {
            return Flux.just(AgentLoop.AgentChunk.error("会话不存在"));
        }

        // 会话忙（有同步任务在跑/排队）时拒绝流式请求，避免并发写乱历史
        if (!dispatcher.tryAcquireStream(request.sessionId())) {
            return Flux.just(AgentLoop.AgentChunk.error("会话正忙，请等待当前任务完成"));
        }

        ThinkingLevel level = parseLevel(request.thinkingLevel());
        String model = request.model();
        if (model == null || model.isBlank()) {
            model = getSavedModel(adapterManager.getActiveAdapter());
        }

        // 首条消息触发会话标题生成（异步，失败有兜底）——同样用用户原话
        titleService.generateAsync(request.sessionId(), request.message());

        // @ 引用展开（和同步接口同一套逻辑，两条路不能有两套行为）
        String message = expandMentions(request.sessionId(), request.message());

        return agentLoop.processMessageStream(
            request.sessionId(),
            message,
            sessionManager.getEffectiveMode(request.sessionId()),
            level,
            model
        ).doFinally(signal -> dispatcher.releaseStream(request.sessionId()));
    }

    /**
     * 展开用户消息里的 @ 引用。
     *
     * <p>展开失败的兜底是"按原消息发下去"：引用读不到文件，不该让整条消息发不出去
     * （用户至少要看到模型的回复，说清楚是文件没找到）。</p>
     *
     * <p>展开记录会写一条事件：不写的话，用户发出去的消息在事件流/界面上还是原样，
     * 会以为 {@code @file:} 没生效、甚至以为消息被吞了。事件用
     * {@code TOOL_CALL_COMPLETE + toolName=context_expand}，界面上的工具行能直接显示，
     * 前端要单独渲染就认这个 toolName。</p>
     */
    private String expandMentions(String sessionId, String message) {
        if (message == null || !MentionResolver.hasMention(message)) {
            return message;   // 没有引用记号：一个正则都不跑（每条消息都走这里，必须便宜）
        }
        try {
            MentionResolver.Expansion expansion = mentionResolver.expand(sessionId, message);
            if (!expansion.changed()) {
                return message;
            }
            String summary = expansion.summary();
            if (!summary.isBlank()) {
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("toolName", "context_expand");
                data.put("result", summary);
                data.put("mentions", expansion.notes().stream().map(n -> n.token()).toList());
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_COMPLETE,
                    data, "已展开引用: " + summary);
            }
            return expansion.message();
        } catch (Exception e) {
            log.warn("@ 引用展开失败，按原消息发送: {}", e.getMessage(), e);
            return message;
        }
    }

    private ThinkingLevel parseLevel(String name) {
        ThinkingLevel level = ThinkingLevel.MEDIUM;
        if (name != null) {
            try {
                level = ThinkingLevel.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        return level;
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
        // 出厂默认：内置本地模型
        return "lion-models1";
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
