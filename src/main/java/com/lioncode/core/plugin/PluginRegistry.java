package com.lioncode.core.plugin;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.event.EventStore;
import com.lioncode.core.event.LionEvent;
import com.lioncode.core.plugin.skill.SkillPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 插件注册表
 * 
 * 统一管理所有插件的注册、加载、发现、卸载。
 * 核心runtime通过注册表获取插件，不硬编码写死工具与技能。
 * 
 * 特性：
 * - 插件注册/注销
 * - 按类型、类别、工作模式过滤
 * - 热加载/卸载支持
 * - 插件发现（按ID、名称、描述搜索）
 * - 事件通知
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final EventStore eventStore;

    /** 事件总线（热插拔时通知订阅者；老代码只用 EventStore，这里补上总线是为了外置插件能感知） */
    private final EventBus eventBus;

    /** 已注册插件映射表：插件ID -> 插件实例 */
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();

    /** 插件类型索引 */
    private final Map<Plugin.PluginType, Set<String>> typeIndex = new ConcurrentHashMap<>();

    /** 工具类别索引 */
    private final Map<ToolPlugin.ToolCategory, Set<String>> categoryIndex = new ConcurrentHashMap<>();

    /** 插件分类索引（"一切皆插件"的 9 类，设置面板按它分组） */
    private final Map<PluginKind, Set<String>> kindIndex = new ConcurrentHashMap<>();

    /**
     * 插件出错信息：插件ID -> 错误。
     *
     * <p>【为什么要记这个】外置 jar 是用户自己写的，initialize() 抛异常、类加载失败、
     * 工具定义非法都很正常。这些插件**照常出现在列表里**，只是带一个 error 字段 ——
     * 用户能在界面上直接看到"这个插件坏了、坏在哪"，
     * 而不是"我明明放了 jar 怎么列表里没有"。
     */
    private final Map<String, String> pluginErrors = new ConcurrentHashMap<>();

    public PluginRegistry(EventStore eventStore, EventBus eventBus) {
        this.eventStore = eventStore;
        this.eventBus = eventBus;
        // 初始化索引
        for (Plugin.PluginType type : Plugin.PluginType.values()) {
            typeIndex.put(type, ConcurrentHashMap.newKeySet());
        }
        for (ToolPlugin.ToolCategory category : ToolPlugin.ToolCategory.values()) {
            categoryIndex.put(category, ConcurrentHashMap.newKeySet());
        }
        for (PluginKind kind : PluginKind.values()) {
            kindIndex.put(kind, ConcurrentHashMap.newKeySet());
        }
    }

    /**
     * 注册插件
     */
    public void register(Plugin plugin) {
        if (plugins.containsKey(plugin.getId())) {
            log.warn("插件已存在，将覆盖: {}", plugin.getId());
            unregister(plugin.getId());
        }

        plugins.put(plugin.getId(), plugin);
        typeIndex.computeIfAbsent(plugin.getType(), k -> ConcurrentHashMap.newKeySet()).add(plugin.getId());

        // 如果是工具插件，添加到类别索引
        if (plugin instanceof ToolPlugin toolPlugin) {
            categoryIndex.computeIfAbsent(toolPlugin.getCategory(), k -> ConcurrentHashMap.newKeySet())
                .add(plugin.getId());
        }

        // 分类索引：getKind() 是派生出来的（工具按"极简模式能不能用"自动分基础/进阶），
        // 但它是插件自己的代码，第三方插件写崩了不能把注册流程带下去。
        PluginKind kind;
        try {
            kind = plugin.getKind();
        } catch (Throwable t) {
            kind = PluginKind.ADVANCED_TOOL;
            pluginErrors.put(plugin.getId(), "getKind() 抛异常: " + t);
            log.warn("插件 {} 的 getKind() 抛异常，按进阶工具归类", plugin.getId(), t);
        }
        kindIndex.computeIfAbsent(kind, k -> ConcurrentHashMap.newKeySet()).add(plugin.getId());

        // 初始化：第三方插件的 initialize() 里可能连数据库、读文件、起线程 —— 什么都可能抛。
        // 抛了也要留在注册表里（带 error 显示），否则用户看不到自己插件为什么没生效。
        try {
            plugin.initialize();
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            pluginErrors.put(plugin.getId(), "初始化失败 " + msg);
            log.error("插件初始化失败（已注册但标记为异常）: {}", plugin.getId(), t);
        }

        // 记录事件
        eventStore.recordEvent("system", LionEvent.EventType.PLUGIN_LOADED,
            Map.of("pluginId", plugin.getId(), "pluginName", plugin.getName(), 
                   "pluginType", plugin.getType().name(),
                   "pluginKind", kind.name()),
            "插件已加载: " + plugin.getName());
        eventBus.publish(EventBus.Events.PLUGIN_LOADED, plugin.getId());

        log.info("插件已注册: {} ({}/{}) - {}", plugin.getId(), plugin.getType(), kind, plugin.getName());
    }

    /**
     * 注销插件（热卸载）
     */
    public boolean unregister(String pluginId) {
        Plugin removed = plugins.remove(pluginId);
        if (removed == null) {
            log.warn("尝试注销不存在的插件: {}", pluginId);
            return false;
        }

        // 从索引中移除
        typeIndex.getOrDefault(removed.getType(), Set.of()).remove(pluginId);
        if (removed instanceof ToolPlugin toolPlugin) {
            categoryIndex.getOrDefault(toolPlugin.getCategory(), Set.of()).remove(pluginId);
        }
        // 分类索引按"所有分类"扫一遍删：卸载时再算一次 getKind() 没必要，而且插件可能已经半死不活
        for (Set<String> ids : kindIndex.values()) {
            ids.remove(pluginId);
        }
        pluginErrors.remove(pluginId);

        // destroy() 同样可能抛（比如它要关的句柄早就没了），不能让一次卸载把调用方打挂
        try {
            removed.destroy();
        } catch (Throwable t) {
            log.warn("插件 destroy() 抛异常（已忽略）: {}", pluginId, t);
        }

        // 记录事件
        eventStore.recordEvent("system", LionEvent.EventType.PLUGIN_UNLOADED,
            Map.of("pluginId", pluginId, "pluginName", removed.getName()),
            "插件已卸载: " + removed.getName());
        eventBus.publish(EventBus.Events.PLUGIN_UNLOADED, pluginId);

        log.info("插件已注销: {}", pluginId);
        return true;
    }

    /**
     * 按分类取插件
     */
    public List<Plugin> getByKind(PluginKind kind) {
        return kindIndex.getOrDefault(kind, Set.of()).stream()
                .map(plugins::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 每个分类各有几个插件（设置面板的分组标题要显示数量）。
     *
     * <p>九类全部返回（哪怕是 0）—— 前端据此渲染"这一类是空的"，
     * 比"这一类不出现"更容易让人发现"我期望的东西没装上"。
     */
    public Map<PluginKind, Integer> kindCounts() {
        Map<PluginKind, Integer> out = new java.util.LinkedHashMap<>();
        for (PluginKind kind : PluginKind.values()) {
            out.put(kind, (int) kindIndex.getOrDefault(kind, Set.of()).stream()
                .filter(id -> plugins.containsKey(id)).count());
        }
        return out;
    }

    /** 插件当前的错误信息（没有就是 null） */
    public String getError(String pluginId) {
        return pluginErrors.get(pluginId);
    }

    /**
     * 工具名 -> 插件ID 的映射。
     *
     * <p>AgentLoop 拿到的是一串**工具名**（{@code read_file}），
     * 而"用户在设置里关掉"针对的是**插件ID**（{@code tool.file.read}）。
     * 这层映射就是两者的桥：关掉哪个插件，就把它贡献的工具名从下发的清单里摘掉。</p>
     */
    public Map<String, String> toolNameToPluginId() {
        Map<String, String> out = new HashMap<>();
        for (ToolPlugin tool : getToolPlugins()) {
            String name = tool.getName();
            if (name != null && !name.isBlank()) {
                out.put(name, tool.getId());
            }
        }
        return out;
    }

    /**
     * 根据ID获取插件
     */
    public Optional<Plugin> getById(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    /**
     * 获取所有已注册插件
     */
    public List<Plugin> getAllPlugins() {
        return List.copyOf(plugins.values());
    }

    /**
     * 获取所有技能插件
     */
    public List<SkillPlugin> getSkillPlugins() {
        return typeIndex.getOrDefault(Plugin.PluginType.SKILL, Set.of()).stream()
                .map(plugins::get)
                .filter(Objects::nonNull)
                .filter(p -> p instanceof SkillPlugin)
                .map(p -> (SkillPlugin) p)
                .toList();
    }

    /**
     * 获取所有工具插件
     */
    public List<ToolPlugin> getToolPlugins() {
        return typeIndex.getOrDefault(Plugin.PluginType.TOOL, Set.of()).stream()
                .map(plugins::get)
                .filter(Objects::nonNull)
                .filter(p -> p instanceof ToolPlugin)
                .map(p -> (ToolPlugin) p)
                .toList();
    }

    /**
     * 根据类别获取工具插件
     */
    public List<ToolPlugin> getToolsByCategory(ToolPlugin.ToolCategory category) {
        return categoryIndex.getOrDefault(category, Set.of()).stream()
                .map(plugins::get)
                .filter(Objects::nonNull)
                .map(p -> (ToolPlugin) p)
                .toList();
    }

    /**
     * 根据工作模式过滤可用工具
     */
    public List<ToolPlugin> getToolsByMode(AgentMode mode) {
        return getToolPlugins().stream()
                .filter(tool -> tool.isAvailableInMode(mode))
                .toList();
    }

    /**
     * 根据工作模式和权限等级过滤工具
     */
    public List<ToolPlugin> getToolsByModeAndPermission(AgentMode mode, 
                                                          ToolPlugin.PermissionLevel permission) {
        return getToolPlugins().stream()
                .filter(tool -> tool.isAvailableInMode(mode))
                .filter(tool -> hasPermission(tool.getRequiredPermission(), permission))
                .toList();
    }

    /**
     * 搜索插件（按名称、描述模糊匹配）
     */
    public List<Plugin> searchPlugins(String keyword) {
        String lower = keyword.toLowerCase();
        return plugins.values().stream()
                .filter(p -> p.getName().toLowerCase().contains(lower) 
                          || p.getDescription().toLowerCase().contains(lower)
                          || p.getId().toLowerCase().contains(lower))
                .toList();
    }

    /**
     * 检查插件是否已注册
     */
    public boolean isRegistered(String pluginId) {
        return plugins.containsKey(pluginId);
    }

    /**
     * 获取已注册插件数量
     */
    public int size() {
        return plugins.size();
    }

    /**
     * 获取插件统计信息
     */
    public PluginStats getStats() {
        long skillCount = plugins.values().stream()
            .filter(p -> p.getType() == Plugin.PluginType.SKILL).count();
        long toolCount = plugins.values().stream()
            .filter(p -> p.getType() == Plugin.PluginType.TOOL).count();
        
        Map<String, Long> categoryCounts = new HashMap<>();
        for (ToolPlugin.ToolCategory cat : ToolPlugin.ToolCategory.values()) {
            long count = getToolsByCategory(cat).size();
            if (count > 0) categoryCounts.put(cat.name(), count);
        }

        return new PluginStats(plugins.size(), skillCount, toolCount, categoryCounts);
    }

    /**
     * 权限检查
     */
    private boolean hasPermission(ToolPlugin.PermissionLevel required, ToolPlugin.PermissionLevel granted) {
        return switch (required) {
            case READ_ONLY -> true; // 只读权限所有人都有
            case WORKSPACE_WRITE -> granted == ToolPlugin.PermissionLevel.WORKSPACE_WRITE 
                                  || granted == ToolPlugin.PermissionLevel.FULL_ACCESS;
            case FULL_ACCESS -> granted == ToolPlugin.PermissionLevel.FULL_ACCESS;
        };
    }

    /**
     * 插件统计信息
     */
    public record PluginStats(
        int totalPlugins,
        long skillCount,
        long toolCount,
        Map<String, Long> categoryCounts
    ) {}
}
