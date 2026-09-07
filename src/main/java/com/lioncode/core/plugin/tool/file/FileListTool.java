package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 目录列表工具
 */
@Component
public class FileListTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.list"; }

    @Override
    public String getName() { return "list_directory"; }

    @Override
    public String getDescription() { return "列出目录下的文件和子目录"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "目录路径"),
                "recursive", Map.of("type", "boolean", "description", "是否递归列出", "default", false),
                "maxDepth", Map.of("type", "integer", "description", "最大递归深度", "default", 3)
            ),
            "required", new String[]{"path"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            boolean recursive = Boolean.TRUE.equals(arguments.get("recursive"));
            int maxDepth = arguments.containsKey("maxDepth") ? 
                ((Number) arguments.get("maxDepth")).intValue() : 3;

            Path dirPath = Path.of(path);
            if (!Files.exists(dirPath)) {
                return error("路径不存在: " + path);
            }
            if (!Files.isDirectory(dirPath)) {
                return error("不是目录: " + path);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(path).append("\n");

            if (recursive) {
                try (var stream = Files.walk(dirPath, maxDepth)) {
                    stream.forEach(p -> {
                        String relative = dirPath.relativize(p).toString();
                        if (relative.isEmpty()) relative = ".";
                        String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                        sb.append(prefix).append(relative).append("\n");
                    });
                }
            } else {
                try (var stream = Files.list(dirPath)) {
                    stream.forEach(p -> {
                        String name = p.getFileName().toString();
                        String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                        sb.append(prefix).append(name).append("\n");
                    });
                }
            }

            return success(sb.toString());

        } catch (IOException e) {
            return error("列出目录失败: " + e.getMessage());
        } catch (Exception e) {
            return error("参数错误: " + e.getMessage());
        }
    }
}
