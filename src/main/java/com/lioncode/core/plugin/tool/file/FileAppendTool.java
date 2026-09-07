package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 文件追加工具
 */
@Component
public class FileAppendTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.append"; }
    @Override
    public String getName() { return "append_file"; }
    @Override
    public String getDescription() { return "向文件末尾追加内容"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_MODIFY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "文件路径"),
            "content", Map.of("type", "string", "description", "追加内容")
        ), "required", new String[]{"path", "content"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String content = getRequiredStringArg(arguments, "content");
            Files.writeString(Path.of(path), content, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return success("内容已追加到: " + path);
        } catch (Exception e) {
            return error("追加失败: " + e.getMessage());
        }
    }
}
