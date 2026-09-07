package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数字进制转换工具
 */
@Component
public class NumberTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.number"; }
    @Override
    public String getName() { return "number_convert"; }
    @Override
    public String getDescription() { return "数字进制转换（二进制/八进制/十进制/十六进制）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "value", Map.of("type", "string", "description", "输入值"),
            "fromBase", Map.of("type", "integer", "description", "源进制"),
            "toBase", Map.of("type", "integer", "description", "目标进制")
        ), "required", new String[]{"value", "fromBase", "toBase"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String value = getRequiredStringArg(arguments, "value");
            int fromBase = ((Number) arguments.get("fromBase")).intValue();
            int toBase = ((Number) arguments.get("toBase")).intValue();
            
            long decimal = Long.parseLong(value, fromBase);
            String result = Long.toString(decimal, toBase);
            
            return success(String.format("%s (base%d) = %s (base%d)", value, fromBase, result, toBase));
        } catch (Exception e) {
            return error("转换失败: " + e.getMessage());
        }
    }
}
