package com.lioncode.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 插件加载器
 * 
 * 负责插件的发现、加载和生命周期管理。
 * 支持从指定目录扫描和加载插件。
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final PluginRegistry pluginRegistry;

    @Value("${lion.plugin.scan-path:}")
    private String scanPath;

    @Value("${lion.plugin.hot-reload:true}")
    private boolean hotReloadEnabled;

    public PluginLoader(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("插件加载器已初始化，热加载: {}", hotReloadEnabled);
        if (scanPath != null && !scanPath.isBlank()) {
            log.info("插件扫描路径: {}", scanPath);
        }
    }

    /**
     * 手动加载插件
     */
    public boolean loadPlugin(Plugin plugin) {
        if (pluginRegistry.isRegistered(plugin.getId())) {
            log.warn("插件已存在: {}", plugin.getId());
            return false;
        }
        pluginRegistry.register(plugin);
        return true;
    }

    /**
     * 手动卸载插件
     */
    public boolean unloadPlugin(String pluginId) {
        return pluginRegistry.unregister(pluginId);
    }

    /**
     * 热重载插件
     */
    public boolean reloadPlugin(String pluginId, Plugin newVersion) {
        if (!hotReloadEnabled) {
            log.warn("热加载已禁用");
            return false;
        }
        
        // 卸载旧版本
        pluginRegistry.unregister(pluginId);
        // 注册新版本
        pluginRegistry.register(newVersion);
        
        log.info("插件已热重载: {}", pluginId);
        return true;
    }

    /**
     * 批量加载插件
     */
    public int loadPlugins(List<Plugin> plugins) {
        int loaded = 0;
        for (Plugin plugin : plugins) {
            if (loadPlugin(plugin)) {
                loaded++;
            }
        }
        log.info("批量加载完成: {}/{}", loaded, plugins.size());
        return loaded;
    }

    /**
     * 获取已加载插件数量
     */
    public int getLoadedCount() {
        return pluginRegistry.size();
    }

    /**
     * 检查热加载是否启用
     */
    public boolean isHotReloadEnabled() {
        return hotReloadEnabled;
    }
}
