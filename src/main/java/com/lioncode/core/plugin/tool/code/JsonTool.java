package com.lioncode.core.plugin.tool.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JSON格式化/验证工具
 */
@Component
public class JsonTool extends AbstractToolPlugin {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getId() { return "tool.code.json"; }
    @Override
    public String getName() { return "json_format"; }
    @Override
    public String getDescription() { return "格式化、验证、压缩JSON"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "JSON字符串"),
            "action", Map.of("type", "string", "description", "format/minify/validate", "default", "format")
        ), "required", new String[]{"input"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String input = getRequiredStringArg(arguments, "input");
            String action = getStringArg(arguments, "action", "format");
            
            return switch (action) {
                case "format" -> success(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(mapper.readTree(input)));
                case "minify" -> success(mapper.writeValueAsString(mapper.readTree(input)));
                case "validate" -> {
                    mapper.readTree(input);
                    yield success("JSON格式有效");
                }
                default -> error("未知操作: " + action);
            };
        } catch (Exception e) {
            return error("JSON处理失败: " + e.getMessage());
        }
    }
}
