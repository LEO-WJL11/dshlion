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
            // 实测：模型把目录当文件传进来，原来只回"统计失败: <路径>"（异常 message 是空的），
            // 等于什么都没说，它下一轮才知道换文件。这里直接把原因写清楚。
            if (Files.isDirectory(Path.of(path))) {
                return error("这是目录，不是文件: " + path + "（先 list_directory/glob_files 找到具体文件）");
            }
            if (!Files.exists(Path.of(path))) {
                return error("文件不存在: " + path);
            }
            String content = readTextFile(Path.of(path));
            long lines = content.chars().filter(c -> c == '\n').count() + 1;
            long words = content.split("\\s+").length;
            long bytes = content.getBytes().length;
            return success(String.format("行数: %d\n字数: %d\n字节数: %d", lines, words, bytes));
        } catch (Exception e) {
            return error("统计失败: " + e.getMessage());
        }
    }
}
