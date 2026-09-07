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
 * 文件复制工具
 */
@Component
public class FileCopyTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(FileCopyTool.class);

    @Override
    public String getId() { return "tool.file.copy"; }

    @Override
    public String getName() { return "copy_file"; }

    @Override
    public String getDescription() { return "复制文件或目录到目标路径"; }

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

            if (Files.isDirectory(sourcePath)) {
                // 复制目录
                Files.createDirectories(targetPath);
                try (var stream = Files.walk(sourcePath)) {
                    stream.forEach(src -> {
                        try {
                            Path dest = targetPath.resolve(sourcePath.relativize(src));
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dest);
                            } else {
                                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            } else {
                // 复制文件
                if (targetPath.getParent() != null) {
                    Files.createDirectories(targetPath.getParent());
                }
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("复制: {} -> {}", source, target);
            return success("已复制: " + source + " -> " + target);

        } catch (Exception e) {
            return error("复制失败: " + e.getMessage());
        }
    }
}
