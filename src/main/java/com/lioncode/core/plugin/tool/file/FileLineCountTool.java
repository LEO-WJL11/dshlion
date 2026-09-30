package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件行数统计工具
 */
@Component
public class FileLineCountTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.linecount"; }
    @Override
    public String getName() { return "line_count"; }
    @Override
    public String getDescription() { return "统计文件行数"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "文件路径")
        ), "required", new String[]{"path"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            Path file = Path.of(path);
            if (Files.isDirectory(file)) {
                // 同 word_count：模型要的是"这个目录里一共多少行"，那就去数
                long total = 0, files = 0;
                try (var walk = Files.walk(file)) {
                    for (Path p : walk.filter(Files::isRegularFile).toList()) {
                        try {
                            total += readTextLines(p).size();
                            files++;
                        } catch (Exception notText) {
                            // 二进制文件跳过
                        }
                    }
                }
                return success("这是目录，已按**目录累计**统计: " + path
                    + "\n文本文件数: " + files + "\n总行数: " + total);
            }
            if (!Files.exists(file)) {
                return error("文件不存在: " + path);
            }
            // 【实测】原来用 Files.lines(Path) 读：它按 UTF-8 严格解码，用户机器上
            // 记事本存的 ANSI/GBK 中文文件直接抛
            // MalformedInputException: Input length = 1，line_count 报"统计失败"。
            // word_count 早就用 readTextLines 容错读了，这里对齐（同一个毛病不该漏一个工具）。
            long count = readTextLines(file).size();
            return success("行数: " + count);
        } catch (Exception e) {
            return error("统计失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
