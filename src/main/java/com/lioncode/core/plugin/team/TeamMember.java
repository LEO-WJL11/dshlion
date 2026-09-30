package com.lioncode.core.plugin.team;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体团队里的一个成员：用哪个模式、负责干什么。
 *
 * @param id      唯一标识（用户不填就自动生成，增删改查都按它定位）
 * @param name    显示名（"代码审查员""文档写手"…）
 * @param mode    工作模式：MINIMAL（极简）/ STANDARD（标准）
 * @param role    职责说明（这句会进系统提示词，让模型知道这个成员该干什么）
 * @param model   该成员用哪个模型（空 = 跟主 Agent 一样）
 * @param enabled 这个成员是否参与
 */
public record TeamMember(
    String id,
    String name,
    String mode,
    String role,
    String model,
    boolean enabled
) {

    /** 新建一个成员（id 留空时自动生成） */
    public static TeamMember create(String id, String name, String mode, String role,
                                    String model, Boolean enabled) {
        return new TeamMember(
            id == null || id.isBlank() ? "agent-" + UUID.randomUUID().toString().substring(0, 8) : id.trim(),
            name == null || name.isBlank() ? "未命名智能体" : name.trim(),
            mode == null || mode.isBlank() ? "STANDARD" : mode.trim().toUpperCase(),
            role == null ? "" : role.trim(),
            model == null ? "" : model.trim(),
            enabled == null || enabled);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("mode", mode);
        m.put("role", role);
        m.put("model", model);
        m.put("enabled", enabled);
        return m;
    }

    public static TeamMember fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        Object enabledRaw = m.get("enabled");
        Boolean enabled = enabledRaw instanceof Boolean b ? b
            : (enabledRaw == null ? Boolean.TRUE : Boolean.valueOf(String.valueOf(enabledRaw)));
        String id = m.get("id") == null ? null : String.valueOf(m.get("id"));
        if (id == null || id.isBlank()) {
            return null;   // 没有 id 的成员没法改也没法删，直接当脏数据丢掉
        }
        return create(id, str(m.get("name")), str(m.get("mode")), str(m.get("role")),
            str(m.get("model")), enabled);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
