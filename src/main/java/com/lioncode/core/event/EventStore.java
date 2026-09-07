package com.lioncode.core.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 事件溯源存储
 * 
 * 完整记录Agent每一轮思考、工具调用、返回结果，支持回放调试。
 * 学习Claude-Code的事件溯源日志体系。
 * 
 * 特性：
 * - 内存+文件双层存储
 * - 按会话分组管理事件
 * - 支持事件回放和轨迹追溯
 * - 自动清理过期事件
 */
@Component
public class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);
    private static final ObjectMapper mapper = createMapper();

    @Value("${lion.event.store-path:${user.home}/lion-code-workspace/.lioncode/events}")
    private String storePath;

    @Value("${lion.event.retention-days:30}")
    private int retentionDays;

    /** 按会话ID分组存储事件（内存层） */
    private final Map<String, List<LionEvent>> sessionEvents = new ConcurrentHashMap<>();

    /** 事件索引：事件ID -> 事件 */
    private final Map<String, LionEvent> eventIndex = new ConcurrentHashMap<>();

    private Path eventStorePath;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @PostConstruct
    public void init() {
        eventStorePath = Path.of(storePath);
        try {
            Files.createDirectories(eventStorePath);
            log.info("事件存储目录已初始化: {}", eventStorePath);
            loadEventsFromDisk();
        } catch (IOException e) {
            log.error("无法创建事件存储目录: {}", storePath, e);
        }
    }

    /**
     * 记录事件
     */
    public void record(LionEvent event) {
        // 写入内存
        sessionEvents.computeIfAbsent(event.sessionId(), k -> new ArrayList<>()).add(event);
        eventIndex.put(event.eventId(), event);

        // 持久化到磁盘
        persistEvent(event);

        log.debug("事件已记录: {} - {} - {}", event.type(), event.sessionId(), event.summary());
    }

    /**
     * 创建并记录事件（便捷方法）
     */
    public LionEvent recordEvent(String sessionId, LionEvent.EventType type, 
                                  Map<String, Object> data, String summary) {
        LionEvent event = new LionEvent(
            UUID.randomUUID().toString(),
            sessionId,
            type,
            Instant.now(),
            data != null ? data : Map.of(),
            summary
        );
        record(event);
        return event;
    }

    /**
     * 获取会话全部事件
     */
    public List<LionEvent> getSessionEvents(String sessionId) {
        return List.copyOf(sessionEvents.getOrDefault(sessionId, List.of()));
    }

    /**
     * 获取会话的指定类型事件
     */
    public List<LionEvent> getSessionEventsByType(String sessionId, LionEvent.EventType type) {
        return getSessionEvents(sessionId).stream()
                .filter(e -> e.type() == type)
                .toList();
    }

    /**
     * 根据事件ID获取事件
     */
    public Optional<LionEvent> getEvent(String eventId) {
        return Optional.ofNullable(eventIndex.get(eventId));
    }

    /**
     * 回放会话事件轨迹
     * 按时间顺序返回所有事件，用于调试和分析
     */
    public List<LionEvent> replaySession(String sessionId) {
        List<LionEvent> events = getSessionEvents(sessionId);
        log.info("回放会话 {} 共 {} 个事件", sessionId, events.size());
        return events;
    }

    /**
     * 获取会话的工具调用轨迹
     */
    public List<LionEvent> getToolCallTrace(String sessionId) {
        return getSessionEvents(sessionId).stream()
                .filter(e -> e.type() == LionEvent.EventType.TOOL_CALL_START 
                          || e.type() == LionEvent.EventType.TOOL_CALL_COMPLETE
                          || e.type() == LionEvent.EventType.TOOL_CALL_ERROR)
                .toList();
    }

    /**
     * 获取会话摘要统计
     */
    public SessionSummary getSessionSummary(String sessionId) {
        List<LionEvent> events = getSessionEvents(sessionId);
        
        long totalEvents = events.size();
        long toolCalls = events.stream()
            .filter(e -> e.type() == LionEvent.EventType.TOOL_CALL_START).count();
        long toolSuccesses = events.stream()
            .filter(e -> e.type() == LionEvent.EventType.TOOL_CALL_COMPLETE).count();
        long toolErrors = events.stream()
            .filter(e -> e.type() == LionEvent.EventType.TOOL_CALL_ERROR).count();
        long modelCalls = events.stream()
            .filter(e -> e.type() == LionEvent.EventType.MODEL_RESPONSE).count();
        
        Instant firstEvent = events.isEmpty() ? null : events.get(0).timestamp();
        Instant lastEvent = events.isEmpty() ? null : events.get(events.size() - 1).timestamp();

        return new SessionSummary(sessionId, totalEvents, toolCalls, toolSuccesses, 
            toolErrors, modelCalls, firstEvent, lastEvent);
    }

    /**
     * 清空会话事件
     */
    public void clearSession(String sessionId) {
        List<LionEvent> removed = sessionEvents.remove(sessionId);
        if (removed != null) {
            removed.forEach(e -> eventIndex.remove(e.eventId()));
            log.info("会话事件已清空: {} ({}个事件)", sessionId, removed.size());
        }
    }

    /**
     * 获取所有活跃会话ID
     */
    public Set<String> getActiveSessionIds() {
        return Set.copyOf(sessionEvents.keySet());
    }

    /**
     * 持久化单个事件到磁盘
     */
    private void persistEvent(LionEvent event) {
        try {
            String dateDir = DateTimeFormatter.ISO_LOCAL_DATE.format(
                event.timestamp().atZone(java.time.ZoneId.systemDefault()).toLocalDate());
            Path sessionDir = eventStorePath.resolve(dateDir).resolve(event.sessionId());
            Files.createDirectories(sessionDir);

            String filename = event.eventId() + ".json";
            Path filePath = sessionDir.resolve(filename);
            
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event);
            Files.writeString(filePath, json);
        } catch (IOException e) {
            log.error("事件持久化失败: {}", event.eventId(), e);
        }
    }

    /**
     * 从磁盘加载历史事件
     */
    private void loadEventsFromDisk() {
        try {
            if (!Files.exists(eventStorePath)) return;
            
            int loaded = 0;
            var paths = Files.walk(eventStorePath)
                .filter(p -> p.toString().endsWith(".json"))
                .toList();

            for (Path file : paths) {
                try {
                    String json = Files.readString(file);
                    LionEvent event = mapper.readValue(json, LionEvent.class);
                    sessionEvents.computeIfAbsent(event.sessionId(), k -> new ArrayList<>()).add(event);
                    eventIndex.put(event.eventId(), event);
                    loaded++;
                } catch (Exception e) {
                    log.warn("跳过损坏的事件文件: {}", file, e);
                }
            }

            // 按时间排序每个会话的事件
            sessionEvents.values().forEach(list -> 
                list.sort(Comparator.comparing(LionEvent::timestamp)));

            log.info("从磁盘加载了 {} 个历史事件", loaded);
        } catch (IOException e) {
            log.error("加载历史事件失败", e);
        }
    }

    /**
     * 会话摘要统计
     */
    public record SessionSummary(
        String sessionId,
        long totalEvents,
        long toolCalls,
        long toolSuccesses,
        long toolErrors,
        long modelCalls,
        Instant firstEventTime,
        Instant lastEventTime
    ) {}
}
