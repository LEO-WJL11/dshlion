package com.lioncode.web.controller;

import com.lioncode.core.context.ApiEnvelope;
import com.lioncode.core.context.MentionSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @ 引用的补全接口。
 *
 * <p>前端在输入框里敲 {@code @} 之后调这个接口拿候选，选中后把 {@code insert} 原样插进输入框；
 * 用户发出消息后，服务端（{@code MentionResolver}）按同一套语法把引用展开成真实上下文。
 * 两边共用 {@code insert} 这个字段，前端不需要自己拼 {@code @file:} 前缀。</p>
 *
 * <p>响应字段（契约见 {@code docs/引用上下文.md}）：
 * {@code {ok, success, items:[{type,id,label,detail,insert}], q, kind, root, limit,
 * truncated, elapsedMs, scannedFiles, counts, note}}，同时 {@code data} 里有一份同样的副本。</p>
 */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private static final Logger log = LoggerFactory.getLogger(ContextController.class);

    private final MentionSearchService searchService;

    public ContextController(MentionSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 检索 @ 候选。
     *
     * @param q         关键词（空 = 返回默认候选）
     * @param kind      file / history / skill / all（默认 all）
     * @param root      工作区根目录（可选；默认用会话绑定的工作区，再退到进程工作目录）
     * @param sessionId 会话 id（可选）
     * @param limit     返回条数（默认 20，最大 50）
     */
    @GetMapping("/mentions")
    public Map<String, Object> mentions(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "root", required = false) String root,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "limit", required = false) Integer limit) {

        MentionSearchService.Result result = searchService.search(q, kind, root, sessionId, limit);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("file", countOf(result.items(), "file"));
        counts.put("history", countOf(result.items(), "history"));
        counts.put("skill", countOf(result.items(), "skill"));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("items", result.items());
        fields.put("q", q == null ? "" : q);
        fields.put("kind", kind == null || kind.isBlank() ? "all" : kind);
        fields.put("root", result.root());
        fields.put("limit", limit == null || limit <= 0 ? 20 : Math.min(limit, 50));
        fields.put("truncated", result.truncated());
        fields.put("elapsedMs", result.elapsedMs());
        fields.put("scannedFiles", result.scannedFiles());
        fields.put("counts", counts);
        if (result.note() != null) {
            fields.put("note", result.note());
        }
        log.debug("@ 引用检索: q='{}' kind={} → {} 条，{} ms，扫描 {} 个文件",
            q, kind, result.items().size(), result.elapsedMs(), result.scannedFiles());
        return ApiEnvelope.ok(fields);
    }

    private static long countOf(List<MentionSearchService.Item> items, String type) {
        return items.stream().filter(i -> type.equals(i.type())).count();
    }
}
