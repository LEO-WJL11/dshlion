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
            long count = Files.lines(Path.of(path)).count();
            return success("行数: " + count);
        } catch (Exception e) {
            return error("统计失败: " + e.getMessage());
        }
    }
}
