package com.lioncode.core.plugin.automation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一条自动化任务："什么时候、在哪个会话里、干什么"。
 *
 * @param id           唯一标识
 * @param name         显示名
 * @param sessionId    在哪个会话里执行（必须明确：自动化最容易出的事故就是"跑错会话"）
 * @param prompt       要发给模型的指令
 * @param enabled      是否启用
 * @param everySeconds 周期秒数（&gt;0 表示每隔这么久跑一次；0 表示不是周期任务）
 * @param at           一次性任务的执行时刻（ISO-8601；null 表示不是一次性任务）
 * @param lastRunAt    上次真正执行的时刻（ISO-8601；null 表示还没跑过）
 */
public record AutomationTask(
    String id,
    String name,
    String sessionId,
    String prompt,
    boolean enabled,
    long everySeconds,
    String at,
    String lastRunAt
) {

    /** 新建（id 留空自动生成） */
    public static AutomationTask create(String id, String name, String sessionId, String prompt,
                                        Boolean enabled, Long everySeconds, String at,
                                        String lastRunAt) {
        return new AutomationTask(
            id == null || id.isBlank() ? "task-" + UUID.randomUUID().toString().substring(0, 8) : id.trim(),
            name == null || name.isBlank() ? "未命名任务" : name.trim(),
            sessionId == null ? "" : sessionId.trim(),
            prompt == null ? "" : prompt.trim(),
            enabled == null || enabled,
            everySeconds == null ? 0L : Math.max(everySeconds, 0L),
            at == null || at.isBlank() ? null : at.trim(),
            lastRunAt == null || lastRunAt.isBlank() ? null : lastRunAt.trim());
    }

    /**
     * 这条任务现在到点了吗。
     *
     * <p>三种情况：
     * <ul>
     *   <li>停用的任务永远不到点；</li>
     *   <li>一次性任务（有 {@code at}）：到点且还没跑过 → 到点。<b>跑过就不再触发</b>
     *       （不做"每天同一时刻"的猜测，用户想要周期就配周期）；</li>
     *   <li>周期任务（{@code everySeconds>0}）：从没跑过 → 立刻算到点（用户刚配好就等一个周期
     *       才动，会让人以为没生效）；跑过 → 距上次执行满一个周期才算到点。</li>
     * </ul>
     *
     * <p>一次性任务和周期任务可以同时配：两个条件谁先满足就用谁（触发一次就够，
     * 由调用方在跑完后调 {@code markRun} 更新 lastRunAt）。</p>
     */
    public boolean isDue(Instant now) {
        if (!enabled || now == null) {
            return false;
        }
        Instant last = parseTime(lastRunAt);
        Instant atInstant = parseTime(at);
        if (atInstant != null && last == null && !now.isBefore(atInstant)) {
            return true;
        }
        if (everySeconds > 0) {
            if (last == null) {
                return true;
            }
            return !now.isBefore(last.plusSeconds(everySeconds));
        }
        return false;
    }

    /** 到点的原因（写日志/界面提示用，别让用户猜"它为什么跑了"） */
    public String dueReason(Instant now) {
        Instant last = parseTime(lastRunAt);
        Instant atInstant = parseTime(at);
        if (atInstant != null && last == null && now != null && !now.isBefore(atInstant)) {
            return "到达设定时间 " + at;
        }
        if (everySeconds > 0 && (last == null || !now.isBefore(last.plusSeconds(everySeconds)))) {
            return last == null ? "周期任务首次触发" : "距上次执行已满 " + everySeconds + " 秒";
        }
        return "未到点";
    }

    /**
     * 宽容解析时间字符串。
     *
     * <p>为什么宽容：时间会从界面输入框、REST 参数、手改的 JSON 三个地方进来。
     * 只认一种格式的话，用户写 {@code 2026-09-30 09:00} 就会被判成"没配时间"，
     * 而任务看起来是配好的 —— 这种"静默不触发"最难排查。认不出来返回 null（等于没配）。</p>
     */
    public static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // 继续试其它格式
        }
        String iso = s.replace(' ', 'T');
        try {
            return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续试"只有日期"
        }
        try {
            return java.time.LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("sessionId", sessionId);
        m.put("prompt", prompt);
        m.put("enabled", enabled);
        m.put("everySeconds", everySeconds);
        m.put("at", at);
        m.put("lastRunAt", lastRunAt);
        return m;
    }

    public static AutomationTask fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        Object idRaw = m.get("id");
        if (idRaw == null || String.valueOf(idRaw).isBlank()) {
            return null;   // 没 id 的任务删不掉也改不了，当脏数据丢
        }
        return create(String.valueOf(idRaw), str(m.get("name")), str(m.get("sessionId")),
            str(m.get("prompt")), bool(m.get("enabled")), num(m.get("everySeconds")),
            str(m.get("at")), str(m.get("lastRunAt")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? null : Boolean.valueOf(String.valueOf(o));
    }

    private static Long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
