package com.lioncode.core.plugin.tool.system;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工作目录工具
 */
@Component
public class WorkingDirTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.system.cwd"; }
    @Override
    public String getName() { return "working_directory"; }
    @Override
    public String getDescription() { return "获取或设置当前工作目录"; }
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
        return success("当前工作目录: " + System.getProperty("user.dir"));
    }
}
