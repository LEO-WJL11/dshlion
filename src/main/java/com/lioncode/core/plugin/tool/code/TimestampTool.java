package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 时间戳工具
 */
@Component
public class TimestampTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.timestamp"; }
    @Override
    public String getName() { return "timestamp"; }
    @Override
    public String getDescription() { return "获取当前时间戳或转换时间格式"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "format", Map.of("type", "string", "description", "日期格式", "default", "yyyy-MM-dd HH:mm:ss")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String format = getStringArg(arguments, "format", "yyyy-MM-dd HH:mm:ss");
        Instant now = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        
        return success(String.format(
            "当前时间: %s\nUnix时间戳: %d\nISO格式: %s",
            ldt.format(DateTimeFormatter.ofPattern(format)),
            now.getEpochSecond(),
            now.toString()));
    }
}
