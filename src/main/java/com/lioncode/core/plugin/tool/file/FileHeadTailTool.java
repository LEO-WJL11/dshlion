package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;
import java.util.Map;

/**
 * 文件头尾查看工具
 */
@Component
public class FileHeadTailTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.headtail"; }
    @Override
    public String getName() { return "head_tail_file"; }
    @Override
    public String getDescription() { return "查看文件头部或尾部N行"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "文件路径"),
            "mode", Map.of("type", "string", "description", "head或tail"),
            "lines", Map.of("type", "integer", "description", "行数", "default", 10)
        ), "required", new String[]{"path", "mode"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String mode = getRequiredStringArg(arguments, "mode");
            int lines = arguments.containsKey("lines") ? ((Number) arguments.get("lines")).intValue() : 10;
            
            List<String> allLines = Files.readAllLines(Path.of(path));
            List<String> result;
            
            if ("head".equals(mode)) {
                result = allLines.subList(0, Math.min(lines, allLines.size()));
            } else {
                result = allLines.subList(Math.max(0, allLines.size() - lines), allLines.size());
            }
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.size(); i++) {
                sb.append(i + 1).append(": ").append(result.get(i)).append("\n");
            }
            return success(sb.toString());
        } catch (Exception e) {
            return error("读取失败: " + e.getMessage());
        }
    }
}
