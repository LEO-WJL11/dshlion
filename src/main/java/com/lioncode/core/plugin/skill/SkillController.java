package com.lioncode.core.plugin.skill;

import com.lioncode.core.context.ApiEnvelope;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理接口（{@code /api/skills}）。
 *
 * <p>为什么这个 Controller 放在 {@code core/plugin/skill/} 而不是 {@code web/controller/}：
 * 技能端点和技能仓库、解析器是一件事（列表字段直接来自 {@link SkillDefinition}），
 * 放一起改字段时不用两头跑。Spring 从 {@code com.lioncode} 开始扫描，放这儿照样注册。</p>
 *
 * <p>响应格式见 {@link ApiEnvelope}：顶层同时给 {@code ok} 和 {@code success}，
 * 数据既在顶层（{@code skills}）也在 {@code data} 里。</p>
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRepository repository;
    private final SessionManager sessionManager;

    public SkillController(SkillRepository repository, SessionManager sessionManager) {
        this.repository = repository;
        this.sessionManager = sessionManager;
    }

    /** 技能列表（含坏掉的、被禁用的，坏的原因在 error 字段里）。 */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("skillsDir", repository.builtinDirString());
        fields.put("userSkillsDir", repository.userDirString());
        fields.put("skills", skillJsons());
        return ApiEnvelope.ok(fields);
    }

    /** 重新扫描技能目录（用户新增/修改 SKILL.md 后不用重启）。 */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        SkillRepository.ReloadResult result = repository.reload();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("count", result.count());
        fields.put("errorCount", result.errors());
        fields.put("skillsDir", repository.builtinDirString());
        fields.put("userSkillsDir", repository.userDirString());
        fields.put("skills", skillJsons());
        log.info("技能已重载：{} 个（{} 个有问题）", result.count(), result.errors());
        return ApiEnvelope.ok("已重新扫描技能目录，共 " + result.count() + " 个技能"
            + (result.errors() > 0 ? "（" + result.errors() + " 个有问题）" : ""), fields);
    }

    /** 打开一个技能（持久化到 app-config.json）。 */
    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable("id") String id) {
        return setEnabled(id, true);
    }

    /** 关掉一个技能（被关掉的技能不进提示词、也不能被 skill_load 加载）。 */
    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable("id") String id) {
        return setEnabled(id, false);
    }

    /** 当前会话钉住的技能。 */
    @GetMapping("/active")
    public Map<String, Object> getActive(@RequestParam(name = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ApiEnvelope.error("缺少 sessionId");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sessionId", sessionId);
        fields.put("skills", repository.activeSkills(sessionId));
        return ApiEnvelope.ok(fields);
    }

    /**
     * 给会话钉住技能：之后这个会话的每一轮都会把它们的正文注入上下文，
     * 不用模型自己去调 skill_load。
     */
    @PostMapping("/active")
    public Map<String, Object> setActive(@RequestBody ActiveRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiEnvelope.error("缺少 sessionId");
        }
        if (sessionManager.getSession(request.sessionId()).isEmpty()) {
            return ApiEnvelope.error("会话不存在: " + request.sessionId());
        }
        List<String> unknown = repository.setActive(request.sessionId(), request.skills());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sessionId", request.sessionId());
        fields.put("skills", repository.activeSkills(request.sessionId()));
        if (!unknown.isEmpty()) {
            fields.put("unknown", unknown);
        }
        String message = unknown.isEmpty()
            ? "已更新本会话的技能"
            : "已更新本会话的技能；这些技能没找到或不可用，已忽略: " + String.join("、", unknown);
        return ApiEnvelope.ok(message, fields);
    }

    private Map<String, Object> setEnabled(String id, boolean enabled) {
        if (!repository.setEnabled(id, enabled)) {
            return ApiEnvelope.error("技能不存在: " + id);
        }
        // 用仓库里真实的 id 回（用户可能写的是 skill.backend 这种老写法）
        String realId = repository.find(id).map(SkillDefinition::id).orElse(id);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", realId);
        fields.put("enabled", enabled);
        return ApiEnvelope.ok(enabled ? "技能已启用: " + realId : "技能已禁用: " + realId, fields);
    }

    /** 技能列表的 JSON 形态（字段名和设计稿一致，多给几个方便前端展示）。 */
    private List<Map<String, Object>> skillJsons() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillDefinition d : repository.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            // name 和 id 是同一个东西（frontmatter 的 name 就是唯一 id）；
            // 两个字段都给，是为了让按 name 写代码的前端也能取到值
            m.put("name", d.id());
            m.put("displayName", d.displayName());
            m.put("description", d.description());
            m.put("whenToUse", d.whenToUse());
            m.put("keywords", d.keywords());
            m.put("tools", d.tools());
            m.put("mode", d.mode());
            m.put("model", d.model());
            m.put("enabled", repository.isEnabled(d.id()) && d.usable());
            m.put("builtin", d.builtin());
            m.put("source", d.sourcePath());
            m.put("error", d.error());
            m.put("bodyPreview", d.bodyPreview(160));
            m.put("version", d.version());
            m.put("tags", d.tags());
            m.put("taskTypes", d.taskTypes());
            m.put("bodyLines", d.body() == null ? 0 : (int) d.body().lines().count());
            out.add(m);
        }
        return out;
    }

    /** 钉技能请求体 */
    public record ActiveRequest(String sessionId, List<String> skills) {}
}
