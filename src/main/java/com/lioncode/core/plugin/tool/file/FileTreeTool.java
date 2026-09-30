package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * 目录树工具
 */
@Component
public class FileTreeTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.tree"; }
    @Override
    public String getName() { return "directory_tree"; }
    @Override
    public String getDescription() { return "以树形结构展示目录"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "目录路径"),
            "maxDepth", Map.of("type", "integer", "description", "最大深度", "default", 3)
        ), "required", new String[]{"path"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            int maxDepth = getIntArg(arguments, "maxDepth", 3);
            
            StringBuilder sb = new StringBuilder();
            buildTree(Path.of(path), "", maxDepth, sb);
            return success(sb.toString());
        } catch (Exception e) {
            return error("生成目录树失败: " + e.getMessage());
        }
    }

    private void buildTree(Path dir, String prefix, int depth, StringBuilder sb) throws IOException {
        if (depth <= 0) return;
        try (var stream = Files.list(dir)) {
            var entries = stream.sorted().toList();
            for (int i = 0; i < entries.size(); i++) {
                Path entry = entries.get(i);
                boolean isLast = i == entries.size() - 1;
                sb.append(prefix).append(isLast ? "└── " : "├── ").append(entry.getFileName());
                if (Files.isDirectory(entry)) {
                    sb.append("/");
                    sb.append("\n");
                    buildTree(entry, prefix + (isLast ? "    " : "│   "), depth - 1, sb);
                } else {
                    sb.append("\n");
                }
            }
        }
    }
}
