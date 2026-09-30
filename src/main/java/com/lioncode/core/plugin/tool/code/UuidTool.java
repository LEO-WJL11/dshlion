package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * UUID生成工具
 */
@Component
public class UuidTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.uuid"; }
    @Override
    public String getName() { return "generate_uuid"; }
    @Override
    public String getDescription() { return "生成UUID"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "count", Map.of("type", "integer", "description", "生成数量", "default", 1)
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        int count = getIntArg(arguments, "count", 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(UUID.randomUUID().toString()).append("\n");
        }
        return success(sb.toString().trim());
    }
}
