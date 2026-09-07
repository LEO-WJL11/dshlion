package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * Base64编解码工具
 */
@Component
public class Base64Tool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.base64"; }
    @Override
    public String getName() { return "base64"; }
    @Override
    public String getDescription() { return "Base64编码/解码"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "输入内容"),
            "action", Map.of("type", "string", "description", "encode/decode")
        ), "required", new String[]{"input", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String input = getRequiredStringArg(arguments, "input");
            String action = getRequiredStringArg(arguments, "action");
            
            return switch (action) {
                case "encode" -> success(Base64.getEncoder().encodeToString(input.getBytes()));
                case "decode" -> success(new String(Base64.getDecoder().decode(input)));
                default -> error("未知操作: " + action);
            };
        } catch (Exception e) {
            return error("Base64处理失败: " + e.getMessage());
        }
    }
}
