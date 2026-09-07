package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 字符串处理工具
 */
@Component
public class StringTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.string"; }
    @Override
    public String getName() { return "string_utils"; }
    @Override
    public String getDescription() { return "字符串处理：大小写转换、trim、长度等"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "输入字符串"),
            "action", Map.of("type", "string", "description", "upper/lower/trim/length/reverse")
        ), "required", new String[]{"input", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String input = getRequiredStringArg(arguments, "input");
        String action = getRequiredStringArg(arguments, "action");
        
        return switch (action) {
            case "upper" -> success(input.toUpperCase());
            case "lower" -> success(input.toLowerCase());
            case "trim" -> success(input.trim());
            case "length" -> success("长度: " + input.length());
            case "reverse" -> success(new StringBuilder(input).reverse().toString());
            default -> error("未知操作: " + action);
        };
    }
}
