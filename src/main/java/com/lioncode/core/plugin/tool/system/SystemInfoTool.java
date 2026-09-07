package com.lioncode.core.plugin.tool.system;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.Map;

/**
 * 系统信息工具
 */
@Component
public class SystemInfoTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.system.info"; }
    @Override
    public String getName() { return "system_info"; }
    @Override
    public String getDescription() { return "获取系统运行时信息"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Runtime rt = Runtime.getRuntime();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        
        return success(String.format(
            "操作系统: %s %s\nJava版本: %s\n可用处理器: %d\n最大内存: %d MB\n已用内存: %d MB\n空闲内存: %d MB\n运行时间: %d 秒",
            System.getProperty("os.name"), System.getProperty("os.version"),
            System.getProperty("java.version"), rt.availableProcessors(),
            rt.maxMemory() / 1024 / 1024, (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
            rt.freeMemory() / 1024 / 1024, runtime.getUptime() / 1000));
    }
}
