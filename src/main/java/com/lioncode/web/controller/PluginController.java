package com.lioncode.web.controller;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginLoader;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 插件管理控制器
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginRegistry pluginRegistry;
    private final PluginLoader pluginLoader;

    public PluginController(PluginRegistry pluginRegistry, PluginLoader pluginLoader) {
        this.pluginRegistry = pluginRegistry;
        this.pluginLoader = pluginLoader;
    }

    /**
     * 获取所有已注册插件
     */
    @GetMapping
    public ApiResponse<List<PluginInfo>> getAllPlugins() {
        List<PluginInfo> plugins = pluginRegistry.getAllPlugins().stream()
            .map(p -> new PluginInfo(p.getId(), p.getName(), p.getDescription(), 
                p.getType().name(), p.getVersion(), p.isInitialized()))
            .toList();
        return ApiResponse.ok(plugins);
    }

    /**
     * 获取插件详情
     */
    @GetMapping("/{pluginId}")
    public ApiResponse<PluginInfo> getPlugin(@PathVariable("pluginId") String pluginId) {
        return pluginRegistry.getById(pluginId)
            .map(p -> ApiResponse.ok(new PluginInfo(p.getId(), p.getName(), p.getDescription(),
                p.getType().name(), p.getVersion(), p.isInitialized())))
            .orElse(ApiResponse.error("插件不存在: " + pluginId));
    }

    /**
     * 手动扫描插件目录并热加载（把JAR放入 lion.plugin.scan-path 后调用）
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
     * 卸载插件（热卸载）
     */
    @DeleteMapping("/{pluginId}")
    public ApiResponse<Void> unloadPlugin(@PathVariable("pluginId") String pluginId) {
        if (pluginLoader.unloadPlugin(pluginId)) {
            return ApiResponse.ok("插件已卸载", null);
        }
        return ApiResponse.error("插件不存在或卸载失败: " + pluginId);
    }

    /**
     * 插件信息DTO
     */
    public record PluginInfo(
        String id,
        String name,
        String description,
        String type,
        String version,
        boolean initialized
    ) {}
}
