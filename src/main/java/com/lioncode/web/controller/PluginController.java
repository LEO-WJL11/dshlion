package com.lioncode.web.controller;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 插件管理控制器
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginRegistry pluginRegistry;

    public PluginController(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
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
