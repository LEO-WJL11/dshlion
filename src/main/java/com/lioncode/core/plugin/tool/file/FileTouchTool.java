package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 创建空文件工具
 */
@Component
public class FileTouchTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.touch"; }
    @Override
    public String getName() { return "create_file"; }
    @Override
    public String getDescription() { return "创建空文件"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }

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
            Path filePath = Path.of(path);
            if (filePath.getParent() != null) Files.createDirectories(filePath.getParent());
            Files.createFile(filePath);
            return success("文件已创建: " + path);
        } catch (FileAlreadyExistsException e) {
            return success("文件已存在: " + arguments.get("path"));
        } catch (Exception e) {
            return error("创建文件失败: " + e.getMessage());
        }
    }
}
