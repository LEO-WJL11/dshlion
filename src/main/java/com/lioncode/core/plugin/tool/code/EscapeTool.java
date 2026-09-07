package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 字符串转义工具
 */
@Component
public class EscapeTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.escape"; }
    @Override
    public String getName() { return "escape_string"; }
    @Override
    public String getDescription() { return "字符串转义/反转义"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "输入字符串"),
            "action", Map.of("type", "string", "description", "escape/unescape"),
            "target", Map.of("type", "string", "description", "目标：html/java/url", "default", "html")
        ), "required", new String[]{"input", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String input = getRequiredStringArg(arguments, "input");
        String action = getRequiredStringArg(arguments, "action");
        String target = getStringArg(arguments, "target", "html");
        
        if ("escape".equals(action)) {
            return switch (target) {
                case "html" -> success(input.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;"));
                case "java" -> success(input.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\t", "\\t"));
                case "url" -> success(java.net.URLEncoder.encode(input, java.nio.charset.StandardCharsets.UTF_8));
                default -> error("未知目标: " + target);
            };
        } else if ("unescape".equals(action)) {
            return switch (target) {
                case "html" -> success(input.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\""));
                case "java" -> success(input.replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\"));
                case "url" -> success(java.net.URLDecoder.decode(input, java.nio.charset.StandardCharsets.UTF_8));
                default -> error("未知目标: " + target);
            };
        }
        return error("未知操作: " + action);
    }
}
