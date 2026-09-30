package com.lioncode.web.controller;

import com.lioncode.core.event.EventStore;
import com.lioncode.core.event.LionEvent;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 事件查询接口
 * 
 * 前端在任务运行期间轮询本接口，实时展示工具调用名称：
 * GET /api/events/{sessionId}?after=<epochMillis>
 * 返回指定时间之后的事件列表（按时间排序）。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventStore eventStore;

    public EventController(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * 获取会话事件（可选after参数过滤：只返回该时间之后的事件）
     * 
     * @param sessionId 会话ID
     * @param afterMillis 只返回该时间戳（毫秒）之后的事件
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<List<LionEvent>> getEvents(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "after", required = false) Long afterMillis) {

        List<LionEvent> events = eventStore.getSessionEvents(sessionId);
        if (afterMillis != null && afterMillis > 0) {
            // 【为什么按毫秒比】前端只能表达毫秒（?after=<ms>），而事件时间戳是带纳秒的。
            // 原来写的是 `timestamp.isAfter(Instant.ofEpochMilli(after))` ——
            // 事件是 12:00:00.123456789、前端给的边界是 12:00:00.123，
            // "纳秒的 .123456789 比 .123 大" 永远成立，于是**每轮轮询都重新返回同一条事件**，
            // UI 就刷出一串重复行（实测：web_search 出现 9 次、ask_user 出现 20 次，
            // 次数正好等于那条工具跑了几秒 ÷ 轮询间隔 800ms）。
            //
            // 现在统一按毫秒比，并且用 >=（含边界）保证"同一毫秒里的事件"不会丢；
            // 边界那一毫秒可能重复返回，由前端按 eventId 去重（UI 的 seenEvents）。
            events = events.stream()
                .filter(e -> e.timestamp() != null
                          && e.timestamp().toEpochMilli() >= afterMillis)
                .toList();
        }
        return ApiResponse.ok("ok", events);
    }
}
