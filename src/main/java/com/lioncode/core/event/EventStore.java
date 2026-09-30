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

    /**
     * 每个会话在内存里最多保留多少条事件（0 = 不限）。
     *
     * <p>【为什么要上限】事件在内存里是**只增不减**的：clearSession() 目前没有任何调用方，
     * 一次长任务动辄上千条事件，跑上几十个会话就是几十万条常驻对象。
     * 前端只在任务运行期间用 {@code ?after=} 拉增量，从来不需要回看几千条历史，
     * 所以超出上限就从最老的开始丢（磁盘上的 JSON 文件一个都不删，回放仍可从磁盘重建）。
     */
    @Value("${lion.event.max-events-per-session:2000}")
    private int maxEventsPerSession;

    /**
     * 按会话ID分组存储事件（内存层）。
     *
     * 【为什么值是 CopyOnWriteArrayList】record() 由会话 worker 线程调用，
     * getSessionEvents() 由 HTTP 轮询线程调用，两者并发。
     * 以前这里是 ConcurrentHashMap + ArrayList 的组合 —— map 是并发的，值不是：
     * 多个 worker 同时追加同一个 ArrayList 会丢事件、甚至抛
     * ArrayIndexOutOfBoundsException；而轮询线程一边 List.copyOf() 一边被追加，
     * 还会抛 ConcurrentModificationException。
     * 事件是"写少读多"，CopyOnWriteArrayList 正好匹配：迭代永远安全，
     * 追加靠内部锁保证不丢。
     */
    private final Map<String, List<LionEvent>> sessionEvents = new ConcurrentHashMap<>();

    /** 事件索引：事件ID -> 事件 */
    private final Map<String, LionEvent> eventIndex = new ConcurrentHashMap<>();

    private Path eventStorePath;

    /** sessionId 缺失时用的占位分组键（避免 ConcurrentHashMap 的 null key 直接 NPE） */
    private static final String UNKNOWN_SESSION = "(unknown)";

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
     *
     * <p>sessionId 为空时不再让 ConcurrentHashMap 抛 NPE（如
     * {@code POST /api/chat/adapter/switch} 不带 sessionId 就会走到这里），
     * 而是归到占位分组里并记一条警告。
     */
    public void record(LionEvent event) {
        if (event == null) {
            return;
        }
        String sessionId = sessionIdOf(event);
        // 写入内存
        List<LionEvent> list = sessionEvents.computeIfAbsent(sessionId,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        list.add(event);
        evictIfTooMany(list);
        eventIndex.put(event.eventId(), event);

        // 持久化到磁盘
        persistEvent(event, sessionId);

        log.debug("事件已记录: {} - {} - {}", event.type(), sessionId, event.summary());
    }

    /**
     * 超出上限时从最老的开始丢（同时把索引里的条目清掉，避免 eventIndex 无界增长）。
     *
     * <p>CopyOnWriteArrayList 的 remove(0) 是 O(n) 拷贝，但只在"超过上限之后每来一条"
     * 触发一次，且上限默认 2000，代价可接受。
     */
    private void evictIfTooMany(List<LionEvent> list) {
        if (maxEventsPerSession <= 0) {
            return;
        }
        while (list.size() > maxEventsPerSession) {
            LionEvent oldest = list.remove(0);
            if (oldest != null && oldest.eventId() != null) {
                eventIndex.remove(oldest.eventId());
            }
        }
    }

    /** 取会话ID，空值归一到占位键 */
    private static String sessionIdOf(LionEvent event) {
        String id = event.sessionId();
        if (id == null || id.isBlank()) {
            log.warn("事件 {} 缺少 sessionId（类型 {}），归入 {} 分组",
                event.eventId(), event.type(), UNKNOWN_SESSION);
            return UNKNOWN_SESSION;
        }
        return id;
    }

    /**
     * 把会话ID清洗成一个**安全的单级路径名**。
     *
     * 【为什么必须做】sessionId 直接来自 URL 路径参数，
     * 而 persistEvent 会把它当成目录名拼到事件存储目录下：
     * sessionId = "..\\..\\Windows\\Temp" 就能把 JSON 写到存储目录之外。
     * 这里只允许字母数字与 . _ -，其余字符一律替换成 '_'，
     * 顺便也避开 Windows 保留名（CON/PRN/AUX/NUL/COM1…）与非法字符 : * ? " &lt; &gt; |。
     */
    static String safePathSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN_SESSION;
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        // 纯点名（"." / ".."）与 Windows 保留设备名会让 createDirectories 失败或被解析成上级目录
        if (cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = cleaned.replace('.', '_');
        }
        String upper = cleaned.toUpperCase();
        if (upper.startsWith("CON") || upper.startsWith("PRN") || upper.startsWith("AUX")
            || upper.startsWith("NUL") || upper.matches("COM[1-9].*") || upper.matches("LPT[1-9].*")) {
            cleaned = "_" + cleaned;
        }
        return cleaned.length() > 96 ? cleaned.substring(0, 96) : cleaned;
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
     *
     * <p>sessionId 为 null 时不能直接查 ConcurrentHashMap（会抛 NPE），
     * 这里统一按"查不到"处理。
     */
    public List<LionEvent> getSessionEvents(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
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
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
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
     *
     * <p>sessionId 会被清洗成安全的单级目录名，eventId 同样处理，
     * 避免 {@code ../} 逃逸或 Windows 非法字符导致整个事件存储不可用。
     */
    private void persistEvent(LionEvent event, String sessionId) {
        try {
            if (eventStorePath == null) {
                return;                                  // init 失败（磁盘不可写）时只留内存层
            }
            String dateDir = DateTimeFormatter.ISO_LOCAL_DATE.format(
                event.timestamp() == null ? LocalDate.now()
                    : event.timestamp().atZone(java.time.ZoneId.systemDefault()).toLocalDate());
            Path sessionDir = eventStorePath.resolve(dateDir)
                .resolve(safePathSegment(sessionId)).normalize();
            if (!sessionDir.startsWith(eventStorePath.normalize())) {
                log.warn("事件存储路径越界，已丢弃: sessionId={}", sessionId);
                return;
            }
            Files.createDirectories(sessionDir);

            String filename = safePathSegment(event.eventId()) + ".json";
            Path filePath = sessionDir.resolve(filename);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event);
            Files.writeString(filePath, json);
        } catch (IOException e) {
            log.error("事件持久化失败: {}", event.eventId(), e);
        }
    }

    /**
     * 从磁盘加载历史事件
     *
     * <p>两处修正：
     * <ol>
     *   <li>{@code Files.walk} 返回的流持有打开的目录句柄，必须关闭 ——
     *       以前直接 {@code .toList()} 不关，在 Windows 上会一直占着事件目录；</li>
     *   <li>{@code lion.event.retention-days} 这个配置以前**没有任何代码读它**，
     *       于是每次启动都会把历史上所有事件全塞进内存，长期使用必然越吃越多。
     *       现在按保留天数跳过过期的日期目录（磁盘文件不动，只控制内存层）。</li>
     * </ol>
     */
    private void loadEventsFromDisk() {
        try {
            if (!Files.exists(eventStorePath)) return;

            LocalDate cutoff = retentionDays > 0
                ? LocalDate.now().minusDays(retentionDays)
                : null;

            List<Path> paths;
            try (var stream = Files.walk(eventStorePath)) {
                paths = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> cutoff == null || !expired(p, cutoff))
                    .toList();
            }

            int loaded = 0;
            for (Path file : paths) {
                try {
                    String json = Files.readString(file);
                    LionEvent event = mapper.readValue(json, LionEvent.class);
                    if (event == null) {
                        continue;
                    }
                    sessionEvents.computeIfAbsent(sessionIdOf(event),
                            k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(event);
                    loaded++;
                } catch (Exception e) {
                    log.warn("跳过损坏的事件文件: {}", file, e);
                }
            }

            // 按时间排序每个会话的事件
            sessionEvents.values().forEach(list ->
                list.sort(Comparator.comparing(LionEvent::timestamp,
                    Comparator.nullsLast(Comparator.naturalOrder()))));

            // 【加载路径也必须限流】只给 record() 加上限是不够的：本机实测有 6.9 万条历史事件，
            // 启动时会把它们全部塞进内存（sessionEvents + eventIndex 各一份引用）。
            // 排序之后按"保留最新的 N 条"裁剪，并重建索引，保证两个结构一致。
            int trimmed = trimToLimit();

            log.info("从磁盘加载了 {} 个历史事件（保留 {} 天内的，超出每会话上限丢弃 {} 条）",
                loaded, retentionDays, trimmed);
        } catch (IOException e) {
            log.error("加载历史事件失败", e);
        }
    }

    /**
     * 把每个会话的事件裁剪到 {@code maxEventsPerSession} 条（保留最新的），
     * 并据此重建 eventIndex。
     *
     * @return 丢弃的条数
     */
    private int trimToLimit() {
        if (maxEventsPerSession <= 0) {
            // 不限流：索引照常建起来
            for (List<LionEvent> list : sessionEvents.values()) {
                for (LionEvent e : list) {
                    if (e.eventId() != null) {
                        eventIndex.put(e.eventId(), e);
                    }
                }
            }
            return 0;
        }
        int dropped = 0;
        Map<String, LionEvent> freshIndex = new HashMap<>();
        for (Map.Entry<String, List<LionEvent>> entry : sessionEvents.entrySet()) {
            List<LionEvent> list = entry.getValue();
            if (list.size() > maxEventsPerSession) {
                dropped += list.size() - maxEventsPerSession;
                List<LionEvent> keep = new ArrayList<>(
                    list.subList(list.size() - maxEventsPerSession, list.size()));
                sessionEvents.put(entry.getKey(),
                    new java.util.concurrent.CopyOnWriteArrayList<>(keep));
                list = keep;
            }
            for (LionEvent e : list) {
                if (e.eventId() != null) {
                    freshIndex.put(e.eventId(), e);
                }
            }
        }
        eventIndex.clear();
        eventIndex.putAll(freshIndex);
        return dropped;
    }

    /**
     * 判断事件文件是否已经过期。
     *
     * <p>按路径里的第一级日期目录（{@code events/2026-09-30/<session>/<id>.json}）判断，
     * 解析不出日期的一律当作"未过期"，宁可多留也不误删。
     */
    private boolean expired(Path file, LocalDate cutoff) {
        try {
            Path rel = eventStorePath.relativize(file);
            if (rel.getNameCount() < 2) {
                return false;
            }
            LocalDate day = LocalDate.parse(rel.getName(0).toString(),
                DateTimeFormatter.ISO_LOCAL_DATE);
            return day.isBefore(cutoff);
        } catch (Exception e) {
            return false;
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
