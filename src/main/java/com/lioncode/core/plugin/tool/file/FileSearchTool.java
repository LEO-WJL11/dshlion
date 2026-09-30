package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文件内容搜索工具
 * 
 * 在文件中搜索指定的文本或正则表达式。
 */
@Component
public class FileSearchTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.search"; }

    @Override
    public String getName() { return "search_in_files"; }

    @Override
    public String getDescription() { return "在文件中搜索文本或正则表达式"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_SEARCH; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "搜索目录"),
                "pattern", Map.of("type", "string", "description", "搜索模式（文本或正则）"),
                "filePattern", Map.of("type", "string", "description", "文件名匹配模式", "default", "*"),
                "useRegex", Map.of("type", "boolean", "description", "是否使用正则表达式", "default", false),
                "maxResults", Map.of("type", "integer", "description", "最大结果数", "default", 50)
            ),
            "required", new String[]{"path", "pattern"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String pattern = getRequiredStringArg(arguments, "pattern");
            String filePattern = getStringArg(arguments, "filePattern", "*");
            boolean useRegex = getBoolArg(arguments, "useRegex", false);
            int maxResults = getIntArg(arguments, "maxResults", 50);

            Path searchDir = Path.of(path);
            if (!Files.exists(searchDir)) {
                return error("路径不存在: " + path);
            }

            Pattern searchPattern = useRegex ? 
                Pattern.compile(pattern) : 
                Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);

            List<String> results = new ArrayList<>();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);

            try (var stream = Files.walk(searchDir, 10)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .forEach(file -> {
                        try {
                            List<String> lines = readTextLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                if (searchPattern.matcher(lines.get(i)).find()) {
                                    String relative = searchDir.relativize(file).toString();
                                    results.add(String.format("%s:%d: %s", 
                                        relative, i + 1, lines.get(i).trim()));
                                    if (results.size() >= maxResults) break;
                                }
                            }
                        } catch (IOException ignored) {}
                        if (results.size() >= maxResults) return;
                    });
            }

            if (results.isEmpty()) {
                return success("未找到匹配结果");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 个匹配:\n");
            results.forEach(r -> sb.append(r).append("\n"));
            return success(sb.toString());

        } catch (Exception e) {
            return error("搜索失败: " + e.getMessage());
        }
    }
}
