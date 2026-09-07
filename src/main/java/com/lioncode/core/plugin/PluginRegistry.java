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

    /** 已注册插件映射表：插件ID -> 插件实例 */
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();

    /** 插件类型索引 */
    private final Map<Plugin.PluginType, Set<String>> typeIndex = new ConcurrentHashMap<>();

    /** 工具类别索引 */
    private final Map<ToolPlugin.ToolCategory, Set<String>> categoryIndex = new ConcurrentHashMap<>();

    public PluginRegistry(EventStore eventStore) {
        this.eventStore = eventStore;
        // 初始化索引
        for (Plugin.PluginType type : Plugin.PluginType.values()) {
            typeIndex.put(type, ConcurrentHashMap.newKeySet());
        }
        for (ToolPlugin.ToolCategory category : ToolPlugin.ToolCategory.values()) {
            categoryIndex.put(category, ConcurrentHashMap.newKeySet());
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

        plugin.initialize();

        // 记录事件
        eventStore.recordEvent("system", LionEvent.EventType.PLUGIN_LOADED,
            Map.of("pluginId", plugin.getId(), "pluginName", plugin.getName(), 
                   "pluginType", plugin.getType().name()),
            "插件已加载: " + plugin.getName());

        log.info("插件已注册: {} ({}) - {}", plugin.getId(), plugin.getType(), plugin.getName());
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

        removed.destroy();

        // 记录事件
        eventStore.recordEvent("system", LionEvent.EventType.PLUGIN_UNLOADED,
            Map.of("pluginId", pluginId, "pluginName", removed.getName()),
            "插件已卸载: " + removed.getName());

        log.info("插件已注销: {}", pluginId);
        return true;
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
