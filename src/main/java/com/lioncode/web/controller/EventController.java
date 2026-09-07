package com.lioncode.web.controller;

import com.lioncode.core.event.EventStore;
import com.lioncode.core.event.LionEvent;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
            Instant after = Instant.ofEpochMilli(afterMillis);
            events = events.stream()
                .filter(e -> e.timestamp() != null && e.timestamp().isAfter(after))
                .toList();
        }
        return ApiResponse.ok("ok", events);
    }
}
