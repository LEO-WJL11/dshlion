package com.lioncode.core.plugin.automation;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginKind;
import com.lioncode.core.plugin.PluginSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自动化任务插件：按设定时间/周期，在指定会话里自动执行任务。
 *
 * <p><b>本轮刻意不做的两件事</b>（都是为了避免和 Lead 的会话循环打架）：
 * <ol>
 *   <li><b>不起线程、不起定时器</b>：真正的执行必须走会话自己的那条串行通道
 *       （否则"自动任务"和"用户正在发的消息"会同时改同一个会话，
 *       最轻是消息乱序，最重是两边各自以为自己在跑）。所以这里只提供
 *       {@link #pollDue(Instant)}：一个**纯函数**，谁在驱动会话谁就来问一句"有到点的吗"。</li>
 *   <li><b>不自己推进 lastRunAt</b>：{@code pollDue} 反复调用必须返回同样的结果（纯函数才可测、
 *       才不会被重复调用搞乱），所以"跑完了"这件事由调用方用 {@link #markRun} 显式回报。
 *       漏调 {@code markRun} 的后果是任务会被重复触发 —— 这一点在接线时必须注意。</li>
 * </ol>
 *
 * <p>它自身是纯配置 + 纯计算，所以默认开启没有副作用：没配任务时 {@code pollDue} 永远返回空表。</p>
 */
@Component
public class AutomationPlugin implements Plugin {

    public static final String PLUGIN_ID = "plugin.automation";

    private static final Logger log = LoggerFactory.getLogger(AutomationPlugin.class);

    private final PluginSettings settings;

    public AutomationPlugin(PluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "automation";
    }

    @Override
    public String getDisplayName() {
        return "自动化任务";
    }

    @Override
    public String getDescription() {
        return "按设定时间或周期，在指定会话里自动执行任务（时间到点判定，执行由主循环驱动）";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.AUTOMATION;
    }

    // ------------------------------------------------------------------
    // 增删改查
    // ------------------------------------------------------------------

    public List<AutomationTask> tasks() {
        List<AutomationTask> out = new ArrayList<>();
        for (Map<String, Object> raw : settings.listOf("automation", "tasks")) {
            AutomationTask t = AutomationTask.fromMap(raw);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    public AutomationTask find(String id) {
        if (id == null) {
            return null;
        }
        return tasks().stream().filter(t -> id.equals(t.id())).findFirst().orElse(null);
    }

    /**
     * 新增任务。
     *
     * <p>校验三件事（都是"配错了就永远不会按预期跑"的典型）：
     * 会话必须填、指令必须填、时间必须能解析。宁可当场报错，
     * 也不要存一条永远不触发的任务 —— 那种任务在界面上看起来一切正常。</p>
     *
     * @throws IllegalArgumentException 参数不合法
     */
    public AutomationTask add(String id, String name, String sessionId, String prompt,
                              Boolean enabled, Long everySeconds, String at) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("必须指定在哪个会话里执行（sessionId 不能为空）");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空（prompt 必填）");
        }
        if (at != null && !at.isBlank() && AutomationTask.parseTime(at) == null) {
            throw new IllegalArgumentException("时间格式无法识别: " + at
                + "（建议 ISO-8601，例如 2026-09-30T09:00 或 2026-09-30T09:00:00+08:00）");
        }
        Long every = everySeconds == null ? 0L : everySeconds;
        if ((at == null || at.isBlank()) && (every == null || every <= 0)) {
            throw new IllegalArgumentException("必须给出「执行时间」或「周期间隔秒数」其中之一");
        }
        AutomationTask created = AutomationTask.create(id, name, sessionId, prompt, enabled,
            every, at, null);
        if (find(created.id()) != null) {
            throw new IllegalArgumentException("任务ID已存在: " + created.id());
        }
        List<Map<String, Object>> list = new ArrayList<>(settings.listOf("automation", "tasks"));
        list.add(created.toMap());
        settings.put("automation", "tasks", list);
        log.info("自动化任务已新增: {}（会话 {}，周期 {}s，定时 {}）",
            created.name(), created.sessionId(), created.everySeconds(), created.at());
        return created;
    }

    /**
     * 部分更新（只改传进来的键）。
     *
     * @return 更新后的任务；id 不存在返回 null
     */
    public AutomationTask update(String id, Map<String, Object> patch) {
        AutomationTask old = find(id);
        if (old == null) {
            return null;
        }
        String at = patch != null && patch.containsKey("at")
            ? (patch.get("at") == null ? null : String.valueOf(patch.get("at"))) : old.at();
        if (at != null && !at.isBlank() && AutomationTask.parseTime(at) == null) {
            throw new IllegalArgumentException("时间格式无法识别: " + at);
        }
        Long every = old.everySeconds();
        if (patch != null && patch.get("everySeconds") != null) {
            try {
                every = Long.parseLong(String.valueOf(patch.get("everySeconds")).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("周期秒数必须是整数: " + patch.get("everySeconds"));
            }
        }
        Boolean enabled = old.enabled();
        if (patch != null && patch.get("enabled") != null) {
            Object v = patch.get("enabled");
            enabled = v instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(v));
        }
        String lastRun = old.lastRunAt();
        if (patch != null && patch.containsKey("lastRunAt")) {
            lastRun = patch.get("lastRunAt") == null ? null : String.valueOf(patch.get("lastRunAt"));
        }
        AutomationTask updated = AutomationTask.create(old.id(),
            pick(patch, "name", old.name()),
            pick(patch, "sessionId", old.sessionId()),
            pick(patch, "prompt", old.prompt()),
            enabled, every, at, lastRun);

        replaceInList(updated);
        return updated;
    }

    /** 删除任务 */
    public boolean remove(String id) {
        List<Map<String, Object>> list = new ArrayList<>(settings.listOf("automation", "tasks"));
        boolean removed = list.removeIf(raw -> {
            AutomationTask t = AutomationTask.fromMap(raw);
            return t != null && t.id().equals(id);
        });
        if (removed) {
            settings.put("automation", "tasks", list);
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 到点判定（纯函数，交给 Lead 的驱动循环调用）
    // ------------------------------------------------------------------

    /**
     * 现在有哪些任务到点了。
     *
     * <p><b>纯函数</b>：不改任何状态、不起线程、不发消息。同样的 now 调多少次结果都一样。
     * 插件被用户关掉时返回空表（关掉就是关掉，不留后门）。</p>
     *
     * <p>TODO(Lead 接线)：在会话驱动循环里（例如每次 AgentLoop 空闲/每条消息处理完之后）
     * 调一次 {@code pollDue(Instant.now())}；对每条 DueTask：
     * <ol>
     *   <li>按 {@code due.sessionId()} 找到会话，把 {@code due.prompt()} 当作一条用户消息
     *       推进那条会话的串行队列（不要绕过队列直接调模型）；</li>
     *   <li>**无论执行成功还是失败**都要调 {@code markRun(due.task().id(), due.dueAt())}，
     *       否则下一轮 pollDue 还会把它当成到点，形成无限重复触发；</li>
     *   <li>会话不存在 / 已被删除时，把这条任务停用（enabled=false）并记一条日志 ——
     *       否则它会永远到点、永远失败。</li>
     * </ol>
     */
    public List<DueTask> pollDue(Instant now) {
        if (!settings.isEnabled(this)) {
            return List.of();
        }
        Instant moment = now == null ? Instant.now() : now;
        List<DueTask> due = new ArrayList<>();
        for (AutomationTask t : tasks()) {
            if (t.isDue(moment)) {
                due.add(new DueTask(t, t.dueReason(moment), moment));
            }
        }
        return due;
    }

    /**
     * 回报"这条任务这次真的跑过了"，把 lastRunAt 推到给定时刻。
     *
     * <p>由调用方在执行完之后调（见 {@link #pollDue} 的说明）。
     * 只推进、不回退：乱序调用（旧时刻后到）不会把时间改回去，避免任务被反复触发。
     */
    public void markRun(String taskId, Instant when) {
        AutomationTask task = find(taskId);
        if (task == null) {
            return;
        }
        Instant moment = when == null ? Instant.now() : when;
        Instant old = AutomationTask.parseTime(task.lastRunAt());
        if (old != null && old.isAfter(moment)) {
            return;
        }
        AutomationTask updated = AutomationTask.create(task.id(), task.name(), task.sessionId(),
            task.prompt(), task.enabled(), task.everySeconds(), task.at(), moment.toString());
        replaceInList(updated);
        log.info("自动化任务已执行: {}（下次判定基准 {}）", task.name(), moment);
    }

    private void replaceInList(AutomationTask updated) {
        List<Map<String, Object>> list = new ArrayList<>(settings.listOf("automation", "tasks"));
        for (int i = 0; i < list.size(); i++) {
            AutomationTask t = AutomationTask.fromMap(list.get(i));
            if (t != null && t.id().equals(updated.id())) {
                list.set(i, updated.toMap());
            }
        }
        settings.put("automation", "tasks", list);
    }

    private static String pick(Map<String, Object> patch, String key, String fallback) {
        if (patch == null || !patch.containsKey(key) || patch.get(key) == null) {
            return fallback;
        }
        return String.valueOf(patch.get(key));
    }
}
