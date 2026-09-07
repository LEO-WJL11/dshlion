package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 创建目录工具
 */
@Component
public class FileMkdirTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.mkdir"; }
    @Override
    public String getName() { return "create_directory"; }
    @Override
    public String getDescription() { return "创建目录（含父目录）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "目录路径")
        ), "required", new String[]{"path"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            Files.createDirectories(Path.of(path));
            return success("目录已创建: " + path);
        } catch (Exception e) {
            return error("创建目录失败: " + e.getMessage());
        }
    }
}
