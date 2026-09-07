package com.lioncode.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 事件总线
 * 
 * 解耦的事件发布/订阅机制，用于插件间通信。
 * 支持同步和异步事件分发。
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** 事件订阅者映射：事件类型 -> 订阅者列表 */
    private final Map<String, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    /**
     * 订阅事件
     */
    public <T> void subscribe(String eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
            .add(handler);
        log.debug("事件订阅: {} (当前订阅者: {})", eventType, 
            subscribers.get(eventType).size());
    }

    /**
     * 取消订阅
     */
    @SuppressWarnings("unchecked")
    public <T> void unsubscribe(String eventType, Consumer<T> handler) {
        List<Consumer<?>> handlers = subscribers.get(eventType);
        if (handlers != null) {
            handlers.remove(handler);
        }
    }

    /**
     * 发布事件（同步）
     */
    @SuppressWarnings("unchecked")
    public <T> void publish(String eventType, T eventData) {
        List<Consumer<?>> handlers = subscribers.get(eventType);
        if (handlers == null || handlers.isEmpty()) {
            log.debug("事件 {} 无订阅者", eventType);
            return;
        }

        log.debug("发布事件: {} ({}个订阅者)", eventType, handlers.size());
        for (Consumer<?> handler : handlers) {
            try {
                ((Consumer<T>) handler).accept(eventData);
            } catch (Exception e) {
                log.error("事件处理异常: {} - {}", eventType, e.getMessage(), e);
            }
        }
    }

    /**
     * 获取已注册的事件类型
     */
    public Set<String> getEventTypes() {
        return Set.copyOf(subscribers.keySet());
    }

    /**
     * 获取事件类型的订阅者数量
     */
    public int getSubscriberCount(String eventType) {
        return subscribers.getOrDefault(eventType, List.of()).size();
    }

    /**
     * 内置事件类型常量
     */
    public static class Events {
        public static final String PLUGIN_LOADED = "plugin.loaded";
        public static final String PLUGIN_UNLOADED = "plugin.unloaded";
        public static final String SESSION_CREATED = "session.created";
        public static final String SESSION_DESTROYED = "session.destroyed";
        public static final String ADAPTER_SWITCHED = "adapter.switched";
        public static final String WORKSPACE_CHANGED = "workspace.changed";
        public static final String TOOL_EXECUTED = "tool.executed";
        public static final String USER_MESSAGE = "user.message";
        public static final String MODEL_RESPONSE = "model.response";
    }
}
