package com.lioncode.web.controller;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginKind;
import com.lioncode.core.plugin.PluginLoader;
import com.lioncode.core.plugin.PluginPaths;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.PluginSettings;
import com.lioncode.core.plugin.automation.AutomationPlugin;
import com.lioncode.core.plugin.automation.AutomationTask;
import com.lioncode.core.plugin.automation.DueTask;
import com.lioncode.core.plugin.dev.PluginDevService;
import com.lioncode.core.plugin.review.ApprovalReviewPlugin;
import com.lioncode.core.plugin.team.AgentTeamPlugin;
import com.lioncode.core.plugin.team.SubAgentPlugin;
import com.lioncode.core.plugin.team.TeamMember;
import com.lioncode.core.plugin.TerminalPlugin;
import com.lioncode.core.plugin.AgentLoopPlugin;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理控制器（"一切皆插件"的门面）。
 *
 * <p>老端点一个都没动：{@code GET /api/plugins} 的 {@code success}/{@code data} 字段、
 * {@code POST /scan}、{@code DELETE /{id}} 都还在原样返回（前端还在用）。
 * 新字段是**加**上去的（{@code ok}/{@code devMode}/{@code pluginsDir}/{@code kinds}/{@code plugins}），
 * 老的读法照样能读到东西 —— 加字段比改字段安全一百倍。</p>
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginRegistry pluginRegistry;
    private final PluginLoader pluginLoader;
    private final PluginSettings pluginSettings;
    private final PluginPaths pluginPaths;
    private final PluginDevService devService;
    private final TerminalPlugin terminalPlugin;
    private final AgentLoopPlugin agentLoopPlugin;
    private final SubAgentPlugin subAgentPlugin;
    private final AgentTeamPlugin agentTeamPlugin;
    private final ApprovalReviewPlugin approvalReviewPlugin;
    private final AutomationPlugin automationPlugin;

    public PluginController(PluginRegistry pluginRegistry, PluginLoader pluginLoader,
                            PluginSettings pluginSettings, PluginPaths pluginPaths,
                            PluginDevService devService, TerminalPlugin terminalPlugin,
                            AgentLoopPlugin agentLoopPlugin, SubAgentPlugin subAgentPlugin,
                            AgentTeamPlugin agentTeamPlugin,
                            ApprovalReviewPlugin approvalReviewPlugin,
                            AutomationPlugin automationPlugin) {
        this.pluginRegistry = pluginRegistry;
        this.pluginLoader = pluginLoader;
        this.pluginSettings = pluginSettings;
        this.pluginPaths = pluginPaths;
        this.devService = devService;
        this.terminalPlugin = terminalPlugin;
        this.agentLoopPlugin = agentLoopPlugin;
        this.subAgentPlugin = subAgentPlugin;
        this.agentTeamPlugin = agentTeamPlugin;
        this.approvalReviewPlugin = approvalReviewPlugin;
        this.automationPlugin = automationPlugin;
    }

    // ==================================================================
    // 插件列表 / 开关
    // ==================================================================

    /**
     * 获取所有已注册插件（外加坏 jar 的错误条目）。
     *
     * <p>返回体同时满足两种读法：老的（{@code success} + {@code data} 数组）和新的
     * （{@code ok} + {@code devMode} + {@code pluginsDir} + {@code kinds} + {@code plugins}）。</p>
     */
    @GetMapping
    public PluginListView getAllPlugins() {
        List<PluginView> views = pluginViews();
        List<LegacyPluginInfo> legacy = pluginRegistry.getAllPlugins().stream()
            .map(p -> new LegacyPluginInfo(p.getId(), p.getName(), p.getDescription(),
                p.getType().name(), p.getVersion(), p.isInitialized()))
            .toList();

        List<KindView> kinds = new ArrayList<>();
        Map<PluginKind, Integer> counts = pluginRegistry.kindCounts();
        for (PluginKind kind : PluginKind.values()) {
            kinds.add(new KindView(kind.name(), kind.getDisplayName(), kind.getDescription(),
                counts.getOrDefault(kind, 0)));
        }

        return new PluginListView(true, "操作成功", legacy, null,
            true, devService.isDevMode(), pluginPaths.pluginsDir().toString(), kinds, views,
            pluginPaths.scanDirs().stream().map(Path::toString).toList());
    }

    /**
     * 获取插件详情（老接口，原样保留）
     */
    @GetMapping("/{pluginId}")
    public ApiResponse<LegacyPluginInfo> getPlugin(@PathVariable("pluginId") String pluginId) {
        return pluginRegistry.getById(pluginId)
            .map(p -> ApiResponse.ok(new LegacyPluginInfo(p.getId(), p.getName(), p.getDescription(),
                p.getType().name(), p.getVersion(), p.isInitialized())))
            .orElse(ApiResponse.error("插件不存在: " + pluginId));
    }

    /**
     * 手动扫描插件目录并热加载（把JAR放入插件目录后调用）
     */
    @PostMapping("/scan")
    public ApiResponse<PluginLoader.ScanReport> scanPlugins() {
        PluginLoader.ScanReport report = pluginLoader.scanAndLoad();
        if (report.errorCount() == 0) {
            return ApiResponse.ok("扫描完成，加载 " + report.loadedCount() + " 个插件", report);
        }
        return ApiResponse.ok("扫描完成，加载 " + report.loadedCount() + " 个插件，"
            + report.errorCount() + " 个问题", report);
    }

    /**
     * 卸载插件（热卸载：从注册表/事件总线/Agent 主循环里摘掉，并关掉它的 ClassLoader）
     */
    @DeleteMapping("/{pluginId}")
    public ApiResponse<Void> unloadPlugin(@PathVariable("pluginId") String pluginId) {
        if (pluginLoader.unloadPlugin(pluginId)) {
            return ApiResponse.ok("插件已卸载", null);
        }
        return ApiResponse.error("插件不存在或卸载失败: " + pluginId);
    }

    /** 打开某个插件（用户在设置里点开关） */
    @PostMapping("/{pluginId}/enable")
    public ToggleResult enablePlugin(@PathVariable("pluginId") String pluginId) {
        return toggle(pluginId, true);
    }

    /** 关掉某个插件 */
    @PostMapping("/{pluginId}/disable")
    public ToggleResult disablePlugin(@PathVariable("pluginId") String pluginId) {
        return toggle(pluginId, false);
    }

    /**
     * 开关的实现。
     *
     * <p>关掉不等于卸载：插件仍然在注册表里（列表上还能看到它、随时能再打开），
     * 只是它的工具不再下发给模型（由 PluginGateSpi 在每一轮实时过滤）。
     * "卸载"是另一件事（DELETE），那会真的把类加载器也丢掉。</p>
     */
    private ToggleResult toggle(String pluginId, boolean enabled) {
        Plugin plugin = pluginRegistry.getById(pluginId).orElse(null);
        boolean defaultVal = plugin == null ? false : plugin.isEnabledByDefault();
        if (plugin == null) {
            // 坏 jar 也能被"打开"（用户可能刚放进来还没 reload），但先要求它至少出现过
            boolean known = pluginLoader.getFailures().stream()
                .anyMatch(f -> pluginId.equals(f.pluginId()) || ("jar:" + f.jar()).equals(pluginId));
            if (!known) {
                return new ToggleResult(false, pluginId, false, "插件不存在: " + pluginId);
            }
        }
        boolean actual = pluginSettings.setEnabled(pluginId, enabled, defaultVal);
        return new ToggleResult(true, pluginId, actual,
            enabled ? "插件已开启" : "插件已关闭（它的工具不再下发给模型）");
    }

    /**
     * 整组开关：把某一类插件一次全开 / 全关。
     *
     * <p>【为什么要这个】"基础工具插件（极简模式的）""进阶工具插件（标准模式的）"
     * 用户是按**一类**来理解的：想"只留极简能用的那批"时，不该让他一个个点 22 个开关。
     * 单插件开关仍然保留（细粒度还是要的），这个是组级快捷方式。</p>
     */
    @PostMapping("/kind/{kind}/enable")
    public Map<String, Object> enableKind(@PathVariable("kind") String kind) {
        return toggleKind(kind, true);
    }

    @PostMapping("/kind/{kind}/disable")
    public Map<String, Object> disableKind(@PathVariable("kind") String kind) {
        return toggleKind(kind, false);
    }

    private Map<String, Object> toggleKind(String kind, boolean enabled) {
        PluginKind target;
        try {
            target = PluginKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Map.of("ok", false, "kind", kind, "message", "没有这一类插件: " + kind);
        }
        List<String> changed = new ArrayList<>();
        for (Plugin p : pluginRegistry.getByKind(target)) {
            pluginSettings.setEnabled(p.getId(), enabled, p.isEnabledByDefault());
            changed.add(p.getId());
        }
        return Map.of("ok", true, "kind", target.name(), "enabled", enabled,
            "count", changed.size(), "ids", changed);
    }

    /**
     * 重新扫描插件目录：先卸掉所有外置插件（连 ClassLoader 一起），再从头扫一遍。
     */
    @PostMapping("/reload")
    public ReloadResult reloadPlugins() {
        PluginLoader.ScanReport report = pluginLoader.reload();
        return new ReloadResult(true, report.loadedCount(), pluginRegistry.size(),
            report.errors(), pluginViews(), "重载完成，加载 " + report.loadedCount() + " 个插件");
    }

    // ==================================================================
    // 设置
    // ==================================================================

    /** 插件设置总览（设置面板打开时拉一次） */
    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> getSettings() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pluginsDir", pluginPaths.pluginsDir().toString());
        out.put("settingsFile", pluginSettings.file().toString());
        out.put("devMode", devService.isDevMode());
        out.put("terminal", terminalSection());
        out.put("loop", loopSection());
        out.put("subagent", subagentSection());
        out.put("review", reviewSection());
        out.put("team", Map.of("members", agentTeamPlugin.members().stream().map(TeamMember::toMap).toList()));
        out.put("automation", Map.of("tasks", automationPlugin.tasks().stream()
            .map(AutomationTask::toMap).toList()));
        return ApiResponse.ok(out);
    }

    /**
     * 改终端限制。
     *
     * <p>{@code maxCommandSeconds}：一条命令最长跑多少秒（默认 300，跟老行为一致）；
     * {@code maxOutputBytes}：最多带回多少字节，0 = 不限制（默认 200000，超出会截断并告知模型）。</p>
     */
    @PostMapping("/settings/terminal")
    public ApiResponse<Map<String, Object>> updateTerminal(@RequestBody Map<String, Object> body) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body != null && body.get("maxCommandSeconds") != null) {
            patch.put("maxCommandSeconds", intOf(body.get("maxCommandSeconds")));
        }
        if (body != null && body.get("maxOutputBytes") != null) {
            patch.put("maxOutputBytes", intOf(body.get("maxOutputBytes")));
        }
        pluginSettings.updateSection("terminal", patch);
        return ApiResponse.ok("终端限制已更新", terminalSection());
    }

    /** 改 Agent 大循环参数（最大轮次 / 工具超时 / 空转容忍轮数） */
    @PostMapping("/settings/loop")
    public ApiResponse<Map<String, Object>> updateLoop(@RequestBody Map<String, Object> body) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body != null && body.get("maxIterations") != null) {
            patch.put("maxIterations", intOf(body.get("maxIterations")));
        }
        if (body != null && body.get("toolTimeoutSeconds") != null) {
            patch.put("toolTimeoutSeconds", intOf(body.get("toolTimeoutSeconds")));
        }
        if (body != null && body.get("silentRounds") != null) {
            patch.put("silentRounds", intOf(body.get("silentRounds")));
        }
        if (body != null && body.get("maxToolsPerRound") != null) {
            patch.put("maxToolsPerRound", intOf(body.get("maxToolsPerRound")));
        }
        pluginSettings.updateSection("loop", patch);
        return ApiResponse.ok("大循环参数已更新", loopSection());
    }

    /** 改子智能体约束（递归层级 / 并发 / 模型） */
    @PostMapping("/settings/subagent")
    public ApiResponse<Map<String, Object>> updateSubagent(@RequestBody Map<String, Object> body) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body != null && body.get("maxDepth") != null) {
            patch.put("maxDepth", intOf(body.get("maxDepth")));
        }
        if (body != null && body.get("maxConcurrency") != null) {
            patch.put("maxConcurrency", intOf(body.get("maxConcurrency")));
        }
        if (body != null && body.get("provider") != null) {
            patch.put("provider", String.valueOf(body.get("provider")));
        }
        if (body != null && body.get("model") != null) {
            patch.put("model", String.valueOf(body.get("model")));
        }
        pluginSettings.updateSection("subagent", patch);
        return ApiResponse.ok("子智能体设置已更新", subagentSection());
    }

    /** 改自动授权审查配置（用哪个模型审、审哪些工具） */
    @PostMapping("/settings/review")
    public ApiResponse<Map<String, Object>> updateReview(@RequestBody Map<String, Object> body) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body != null && body.get("provider") != null) {
            patch.put("provider", String.valueOf(body.get("provider")));
        }
        if (body != null && body.get("model") != null) {
            patch.put("model", String.valueOf(body.get("model")));
        }
        if (body != null && body.get("tools") instanceof List<?> tools) {
            List<String> names = new ArrayList<>();
            for (Object t : tools) {
                if (t != null && !String.valueOf(t).isBlank()) {
                    names.add(String.valueOf(t).trim());
                }
            }
            patch.put("tools", names);
        }
        pluginSettings.updateSection("review", patch);
        return ApiResponse.ok("审查设置已更新", reviewSection());
    }

    // ==================================================================
    // 智能体团队
    // ==================================================================

    @GetMapping("/team")
    public ApiResponse<List<Map<String, Object>>> listTeam() {
        return ApiResponse.ok(agentTeamPlugin.members().stream().map(TeamMember::toMap).toList());
    }

    /** 新增团队成员 */
    @PostMapping("/team")
    public ApiResponse<Map<String, Object>> addTeamMember(@RequestBody Map<String, Object> body) {
        try {
            TeamMember m = agentTeamPlugin.add(
                str(body, "id"), str(body, "name"), str(body, "mode"),
                str(body, "role"), str(body, "model"), boolOf(body.get("enabled")));
            return ApiResponse.ok("智能体已加入团队", m.toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 修改团队成员（只改传进来的字段） */
    @PostMapping("/team/{id}")
    public ApiResponse<Map<String, Object>> updateTeamMember(@PathVariable("id") String id,
                                                             @RequestBody Map<String, Object> body) {
        try {
            TeamMember m = agentTeamPlugin.update(id, body);
            return m == null ? ApiResponse.error("智能体不存在: " + id)
                : ApiResponse.ok("已更新", m.toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 移除团队成员 */
    @DeleteMapping("/team/{id}")
    public ApiResponse<Map<String, Object>> removeTeamMember(@PathVariable("id") String id) {
        boolean removed = agentTeamPlugin.remove(id);
        return removed ? ApiResponse.ok("已移除", Map.of("id", id))
            : ApiResponse.error("智能体不存在: " + id);
    }

    // ==================================================================
    // 自动化任务
    // ==================================================================

    @GetMapping("/automation")
    public ApiResponse<Map<String, Object>> listAutomation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", automationPlugin.tasks().stream().map(AutomationTask::toMap).toList());
        // 顺手把"现在到点的"也带上：界面上可以直接显示"下次触发/已到点"，
        // 而不用自己再算一遍时间（算时间这种事最容易两边不一致）。
        out.put("due", dueViews(Instant.now()));
        return ApiResponse.ok(out);
    }

    /** 只问到点情况（纯查询，不会执行任何任务） */
    @GetMapping("/automation/due")
    public ApiResponse<List<Map<String, Object>>> dueAutomation() {
        return ApiResponse.ok(dueViews(Instant.now()));
    }

    /** 新增自动化任务 */
    @PostMapping("/automation")
    public ApiResponse<Map<String, Object>> addAutomation(@RequestBody Map<String, Object> body) {
        try {
            AutomationTask t = automationPlugin.add(
                str(body, "id"), str(body, "name"), str(body, "sessionId"), str(body, "prompt"),
                boolOf(body.get("enabled")), longOf(body.get("everySeconds")), str(body, "at"));
            return ApiResponse.ok("任务已创建", t.toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 修改自动化任务 */
    @PostMapping("/automation/{id}")
    public ApiResponse<Map<String, Object>> updateAutomation(@PathVariable("id") String id,
                                                             @RequestBody Map<String, Object> body) {
        try {
            AutomationTask t = automationPlugin.update(id, body);
            return t == null ? ApiResponse.error("任务不存在: " + id)
                : ApiResponse.ok("已更新", t.toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 删除自动化任务 */
    @DeleteMapping("/automation/{id}")
    public ApiResponse<Map<String, Object>> removeAutomation(@PathVariable("id") String id) {
        boolean removed = automationPlugin.remove(id);
        return removed ? ApiResponse.ok("已删除", Map.of("id", id))
            : ApiResponse.error("任务不存在: " + id);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 组装插件视图：注册表里的插件 + 加载失败的 jar。
     *
     * <p>失败的 jar 用 {@code jar:<文件名>} 当 id 显示出来 —— 这是"我明明放了 jar
     * 怎么列表里什么都没有"的唯一解药：让它出现在列表里，带着错误信息。</p>
     */
    private List<PluginView> pluginViews() {
        List<PluginView> out = new ArrayList<>();
        for (Plugin p : pluginRegistry.getAllPlugins()) {
            boolean enabled = pluginSettings.isEnabled(p);
            PluginLoader.ExternalOrigin origin = pluginLoader.origin(p.getId());
            boolean external = origin != null;
            PluginKind kind = safeKind(p);
            out.add(new PluginView(p.getId(), p.getName(), kind.name(), kind.getDisplayName(),
                p.getDescription(), p.getVersion(), enabled, !external, external || p.isHotReloadable(),
                pluginRegistry.getError(p.getId()), external ? "external" : p.getSource(),
                p.getDisplayName(), false));
        }
        for (PluginLoader.PluginLoadFailure f : pluginLoader.getFailures()) {
            String id = f.pluginId() == null || f.pluginId().isBlank() || f.pluginId().contains(".")
                ? "jar:" + f.jar()
                : "jar:" + f.jar() + ":" + f.pluginId();
            out.add(new PluginView(id, f.jar(), "UNKNOWN", "加载失败",
                "这个 jar 没能加载成功，错误见 error 字段", "-", false, false, false,
                f.message(), "external", f.jar() + "（加载失败）", true));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static PluginKind safeKind(Plugin p) {
        try {
            PluginKind k = p.getKind();
            return k == null ? PluginKind.ADVANCED_TOOL : k;
        } catch (Throwable t) {
            return PluginKind.ADVANCED_TOOL;
        }
    }

    private Map<String, Object> terminalSection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxCommandSeconds", terminalPlugin.maxCommandSeconds());
        m.put("maxOutputBytes", terminalPlugin.maxOutputBytes());
        m.put("defaultMaxCommandSeconds", PluginSettings.DEFAULT_MAX_COMMAND_SECONDS);
        m.put("defaultMaxOutputBytes", PluginSettings.DEFAULT_MAX_OUTPUT_BYTES);
        m.put("ownedTools", TerminalPlugin.OWNED_TOOL_NAMES);
        return m;
    }

    private Map<String, Object> loopSection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxIterations", agentLoopPlugin.maxIterations());
        m.put("toolTimeoutSeconds", agentLoopPlugin.toolTimeoutSeconds());
        m.put("silentRounds", agentLoopPlugin.silentRounds());
        m.put("defaultMaxIterations", PluginSettings.DEFAULT_MAX_ITERATIONS);
        m.put("defaultToolTimeoutSeconds", PluginSettings.DEFAULT_TOOL_TIMEOUT_SECONDS);
        m.put("defaultSilentRounds", PluginSettings.DEFAULT_SILENT_ROUNDS);
        return m;
    }

    private Map<String, Object> subagentSection() {
        var cfg = subAgentPlugin.config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("maxDepth", cfg.maxDepth());
        m.put("maxConcurrency", cfg.maxConcurrency());
        m.put("provider", cfg.provider());
        m.put("model", cfg.model());
        return m;
    }

    private Map<String, Object> reviewSection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", approvalReviewPlugin.isActive());
        m.put("provider", approvalReviewPlugin.provider());
        m.put("model", approvalReviewPlugin.model());
        m.put("tools", approvalReviewPlugin.reviewedTools());
        m.put("defaultTools", ApprovalReviewPlugin.DEFAULT_REVIEW_TOOLS);
        return m;
    }

    private List<Map<String, Object>> dueViews(Instant now) {
        List<Map<String, Object>> due = new ArrayList<>();
        for (DueTask d : automationPlugin.pollDue(now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.task().id());
            m.put("name", d.task().name());
            m.put("sessionId", d.sessionId());
            m.put("prompt", d.prompt());
            m.put("reason", d.reason());
            m.put("dueAt", d.dueAt().toString());
            due.add(m);
        }
        return due;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }

    private static Boolean boolOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.valueOf(String.valueOf(v));
    }

    private static Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(Object v) {
        Long l = longOf(v);
        return l == null ? 0 : l.intValue();
    }

    // ==================================================================
    // DTO
    // ==================================================================

    /** 老字段，一个字都没改（前端读的是 type/name/description）。 */
    public record LegacyPluginInfo(
        String id,
        String name,
        String description,
        String type,
        String version,
        boolean initialized
    ) {}

    /** 新视图：设置面板用这一份就够（含开关状态、来源、错误）。 */
    public record PluginView(
        String id,
        String name,
        String kind,
        String kindDisplayName,
        String description,
        String version,
        boolean enabled,
        boolean builtin,
        boolean hotReloadable,
        String error,
        String source,
        String displayName,
        boolean broken
    ) {}

    public record KindView(String name, String displayName, String description, int count) {}

    /**
     * 列表响应：同时是老格式（success/message/data/error）和新格式（ok/…/plugins）。
     */
    public record PluginListView(
        boolean success,
        String message,
        List<LegacyPluginInfo> data,
        String error,
        boolean ok,
        boolean devMode,
        String pluginsDir,
        List<KindView> kinds,
        List<PluginView> plugins,
        List<String> scanDirs
    ) {}

    public record ToggleResult(boolean ok, String id, boolean enabled, String message) {}

    public record ReloadResult(boolean ok, int count, int total, List<String> errors,
                               List<PluginView> plugins, String message) {}
}
