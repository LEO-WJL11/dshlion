package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Cron表达式解析工具
 */
@Component
public class CronTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.cron"; }
    @Override
    public String getName() { return "cron_parse"; }
    @Override
    public String getDescription() { return "解析Cron表达式"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "expression", Map.of("type", "string", "description", "Cron表达式")
        ), "required", new String[]{"expression"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String expr = getRequiredStringArg(arguments, "expression");
        String[] parts = expr.split("\\s+");
        
        if (parts.length < 5 || parts.length > 6) {
            return error("无效的Cron表达式，需要5或6个字段");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Cron表达式: ").append(expr).append("\n");
        sb.append("秒: ").append(parts.length > 5 ? parts[0] : "0").append("\n");
        sb.append("分: ").append(parts.length > 5 ? parts[1] : parts[0]).append("\n");
        sb.append("时: ").append(parts.length > 5 ? parts[2] : parts[1]).append("\n");
        sb.append("日: ").append(parts.length > 5 ? parts[3] : parts[2]).append("\n");
        sb.append("月: ").append(parts.length > 5 ? parts[4] : parts[3]).append("\n");
        sb.append("周: ").append(parts.length > 5 ? parts[5] : parts[4]);
        
        return success(sb.toString());
    }
}
