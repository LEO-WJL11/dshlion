package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 文件统计工具（行数、字数、字节数）
 */
@Component
public class FileWcTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.wc"; }
    @Override
    public String getName() { return "word_count"; }
    @Override
    public String getDescription() { return "统计文件行数、字数、字节数"; }
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
            String content = Files.readString(Path.of(path));
            long lines = content.chars().filter(c -> c == '\n').count() + 1;
            long words = content.split("\\s+").length;
            long bytes = content.getBytes().length;
            return success(String.format("行数: %d\n字数: %d\n字节数: %d", lines, words, bytes));
        } catch (Exception e) {
            return error("统计失败: " + e.getMessage());
        }
    }
}
