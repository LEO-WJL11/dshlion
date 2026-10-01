package com.lioncode.web.controller;

import com.lioncode.core.agent.ContextBudget;
import com.lioncode.core.agent.change.ChangeReview;
import com.lioncode.core.agent.change.PendingChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 改动审核 + 上下文预算的接口。
 *
 * <p>【为什么这两个在一起】它们是同一件事的两面：Agent 干活时要花钱（上下文窗口），
 * 花完的产出要不要落地得人来点头。VS Code 侧栏和 WebUI 都靠这几个接口：</p>
 * <ul>
 *   <li>{@code GET  /api/changes?sessionId=&includeDecided=} —— 待审改动列表（带 diff）</li>
 *   <li>{@code POST /api/changes/{id}/approve} —— 通过：真落盘，并把结论告诉会话里的模型</li>
 *   <li>{@code POST /api/changes/{id}/reject} —— 打回：不落盘，理由回给模型让它重写</li>
 *   <li>{@code GET  /api/context?sessionId=} —— 当前上下文窗口（默认值 / 会话覆盖）</li>
 *   <li>{@code POST /api/context} —— 手动设窗口（界面上也能调，不必非让模型自己调）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChangeReviewController {

    private static final Logger log = LoggerFactory.getLogger(ChangeReviewController.class);

    private final ChangeReview changes;
    private final ContextBudget budget;

    public ChangeReviewController(ChangeReview changes, ContextBudget budget) {
        this.changes = changes;
        this.budget = budget;
    }

    @GetMapping("/changes")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "false") boolean includeDecided) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PendingChange c : changes.list(sessionId, includeDecided)) {
            items.add(c.summary());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("enabled", changes.enabled());
        body.put("changes", items);
        body.put("pendingCount", changes.list(sessionId, false).size());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/changes/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        try {
            PendingChange c = changes.approve(id);
            return ResponseEntity.ok(ok("已通过并写入：" + c.path(), c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (Exception e) {
            log.warn("通过改动 {} 失败", id, e);
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    @PostMapping("/changes/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        try {
            PendingChange c = changes.reject(id, reason);
            return ResponseEntity.ok(ok("已打回，模型会按理由重写：" + c.path(), c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(err(e.getMessage()));
        } catch (Exception e) {
            log.warn("打回改动 {} 失败", id, e);
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    /** 一条改动的完整内容（界面展开看整份 diff/全文时用） */
    @GetMapping("/changes/{id}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable String id) {
        PendingChange c = changes.get(id);
        if (c == null) {
            return ResponseEntity.status(404).body(err("没有这条改动：" + id));
        }
        Map<String, Object> m = c.summary();
        m.put("oldContent", c.oldContent());
        m.put("newContent", c.newContent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("change", m);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context(@RequestParam(required = false) String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("context", budget.snapshot(sessionId));
        return ResponseEntity.ok(body);
    }

    /** 手动设窗口：tokens=0 表示不设限；不带 tokens 表示回到默认 */
    @PostMapping("/context")
    public ResponseEntity<Map<String, Object>> setContext(@RequestBody(required = false) Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.getOrDefault("sessionId", ""));
        Object raw = body == null ? null : body.get("tokens");
        if (raw == null) {
            budget.reset(sessionId);
        } else {
            int tokens;
            try {
                tokens = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(err("tokens 必须是整数"));
            }
            if (tokens != 0 && tokens < 4096) {
                return ResponseEntity.badRequest().body(err("窗口至少 4096 token（再小连系统提示都放不下）"));
            }
            budget.set(sessionId, tokens);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("context", budget.snapshot(sessionId));
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> ok(String message, PendingChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("message", message);
        m.put("change", c.summary());
        return m;
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", message);
        return m;
    }
}
