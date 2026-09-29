package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 时间工具。
 *
 * <p>实测问题（用户日志）：模型传 {@code format=%Y-%m-%d %H:%M:%S}（strftime 风格），
 * 结果输出成了 {@code %2026-%58-%29 %12:%9:%2} —— 因为直接把它当成 Java 的
 * {@link DateTimeFormatter} 模式用，{@code %Y} 之类的记号被当成字面量拆开了。
 *
 * <p>现在：先认 strftime 记号（%Y %m %d %H %M %S %s %j %p %A %B %e），翻译成 Java 模式；
 * 已经是 Java 模式（yyyy/MM/dd…）就原样用；也认 unix/iso/date/time 这种口语别名；
 * 实在认不出就报一句能照着改的错，而不是吐一串乱码。
 */
@Component
public class TimestampTool extends AbstractToolPlugin {

    private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss";

    @Override
    public String getId() { return "tool.code.timestamp"; }
    @Override
    public String getName() { return "timestamp"; }
    @Override
    public String getDescription() { return "获取当前时间（支持 yyyy-MM-dd HH:mm:ss 或 %Y-%m-%d 两种写法）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "format", Map.of("type", "string",
                "description", "时间格式：Java 模式 yyyy-MM-dd HH:mm:ss，或 strftime 风格 %Y-%m-%d %H:%M:%S",
                "default", DEFAULT_FORMAT)
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String raw = getStringArg(arguments, "format", DEFAULT_FORMAT);
        Instant now = Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(now, ZoneId.systemDefault());

        String format = normalize(raw);
        String formatted;
        try {
            formatted = ldt.format(DateTimeFormatter.ofPattern(format));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return error("时间格式无法识别: " + raw
                + "。请用 Java 模式（yyyy-MM-dd HH:mm:ss）或 strftime 风格（%Y-%m-%d %H:%M:%S）");
        }
        return success("当前时间: " + formatted
            + "\nUnix时间戳: " + now.getEpochSecond()
            + "\nISO格式: " + now);
    }

    /** 把 strftime / 口语别名统一成 Java 的 DateTimeFormatter 模式。 */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_FORMAT;
        }
        String s = raw.trim();
        switch (s.toLowerCase()) {
            case "unix", "epoch", "timestamp" -> { return "yyyy-MM-dd HH:mm:ss"; }
            case "iso", "iso8601", "iso-8601" -> { return "yyyy-MM-dd'T'HH:mm:ss"; }
            case "date" -> { return "yyyy-MM-dd"; }
            case "time" -> { return "HH:mm:ss"; }
            case "full", "datetime" -> { return DEFAULT_FORMAT; }
            default -> { /* 继续往下翻译 */ }
        }
        // strftime 风格：按记号逐个翻译（顺序重要，长的先替换）
        if (s.contains("%")) {
            String[][] tokens = {
                {"%Y", "yyyy"}, {"%y", "yy"}, {"%m", "MM"}, {"%d", "dd"},
                {"%H", "HH"}, {"%I", "hh"}, {"%M", "mm"}, {"%S", "ss"},
                {"%p", "a"}, {"%A", "EEEE"}, {"%a", "EEE"}, {"%B", "MMMM"}, {"%b", "MMM"},
                {"%j", "DDD"}, {"%e", "d"}, {"%F", "yyyy-MM-dd"}, {"%T", "HH:mm:ss"},
                {"%R", "HH:mm"}, {"%D", "MM/dd/yy"}, {"%n", "\n"}, {"%t", "\t"},
                {"%%", "%"},
            };
            for (String[] pair : tokens) {
                s = s.replace(pair[0], pair[1]);
            }
            if (s.contains("%")) {
                s = s.replace("%", "");   // 剩下的没见过的记号：去掉，别留成乱码
            }
            return s;
        }
        return s;
    }
}
