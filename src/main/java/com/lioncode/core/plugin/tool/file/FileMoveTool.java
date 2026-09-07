package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * 文件移动/重命名工具
 */
@Component
public class FileMoveTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(FileMoveTool.class);

    @Override
    public String getId() { return "tool.file.move"; }

    @Override
    public String getName() { return "move_file"; }

    @Override
    public String getDescription() { return "移动或重命名文件/目录"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "source", Map.of("type", "string", "description", "源路径"),
                "target", Map.of("type", "string", "description", "目标路径")
            ),
            "required", new String[]{"source", "target"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String source = resolvePath(getRequiredStringArg(arguments, "source"));
            String target = resolvePath(getRequiredStringArg(arguments, "target"));

            Path sourcePath = Path.of(source);
            Path targetPath = Path.of(target);

            if (!Files.exists(sourcePath)) {
                return error("源路径不存在: " + source);
            }

            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("移动: {} -> {}", source, target);
            return success("已移动: " + source + " -> " + target);

        } catch (IOException e) {
            return error("移动失败: " + e.getMessage());
        } catch (Exception e) {
            return error("参数错误: " + e.getMessage());
        }
    }
}
