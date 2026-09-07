package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件路径匹配工具（Glob）
 * 
 * 使用glob模式匹配文件路径。
 */
@Component
public class FileGlobTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.glob"; }

    @Override
    public String getName() { return "glob_files"; }

    @Override
    public String getDescription() { return "使用glob模式匹配文件路径"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_SEARCH; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "搜索根目录"),
                "pattern", Map.of("type", "string", "description", "glob模式，如 **/*.java"),
                "maxResults", Map.of("type", "integer", "description", "最大结果数", "default", 100)
            ),
            "required", new String[]{"path", "pattern"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String pattern = getRequiredStringArg(arguments, "pattern");
            int maxResults = arguments.containsKey("maxResults") ? 
                ((Number) arguments.get("maxResults")).intValue() : 100;

            Path searchDir = Path.of(path);
            if (!Files.exists(searchDir)) {
                return error("路径不存在: " + path);
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> results = new ArrayList<>();

            try (var stream = Files.walk(searchDir, 10)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(searchDir.relativize(p)))
                    .forEach(p -> {
                        if (results.size() < maxResults) {
                            results.add(searchDir.relativize(p).toString());
                        }
                    });
            }

            if (results.isEmpty()) {
                return success("未找到匹配文件");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 个文件:\n");
            results.forEach(r -> sb.append(r).append("\n"));
            return success(sb.toString());

        } catch (Exception e) {
            return error("匹配失败: " + e.getMessage());
        }
    }
}
