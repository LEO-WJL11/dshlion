package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * YAML处理工具
 */
@Component
public class YamlTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.yaml"; }
    @Override
    public String getName() { return "yaml_process"; }
    @Override
    public String getDescription() { return "YAML格式化和验证"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "YAML内容"),
            "action", Map.of("type", "string", "description", "format/validate", "default", "format")
        ), "required", new String[]{"input"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String input = getRequiredStringArg(arguments, "arguments");
        // 简化实现
        return success("YAML处理完成（简化实现）:\n" + input);
    }
}
