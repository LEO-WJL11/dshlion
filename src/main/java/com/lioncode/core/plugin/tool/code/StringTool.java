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
            "action", Map.of("type", "string", "description",
                "只支持这几个：upper / lower / trim / length / reverse")
        ), "required", new String[]{"input", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String input = getRequiredStringArg(arguments, "input");
        String action = getRequiredStringArg(arguments, "action").toLowerCase().trim();
        // 别名归一：模型很爱写 uppercase / to_upper / lowercase / to_lower / len 这些
        // （实测它连着写了 8 次不同写法，全部"未知操作"）。认下来比让它反复试划算。
        action = switch (action) {
            case "uppercase", "to_upper", "to_uppercase", "upcase", "大写" -> "upper";
            case "lowercase", "to_lower", "to_lowercase", "downcase", "小写" -> "lower";
            case "strip", "去除空格" -> "trim";
            case "len", "size", "长度" -> "length";
            case "revert", "翻转", "反转" -> "reverse";
            default -> action;
        };

        return switch (action) {
            case "upper" -> success(input.toUpperCase());
            case "lower" -> success(input.toLowerCase());
            case "trim" -> success(input.trim());
            case "length" -> success("长度: " + input.length());
            case "reverse" -> success(new StringBuilder(input).reverse().toString());
            default -> error("未知操作: " + action + "。本工具只支持：upper、lower、trim、length、reverse");
        };
    }
}
