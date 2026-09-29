package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文件内容修改工具
 * 
 * 支持替换指定行、插入内容、删除行等操作。
 */
@Component
public class FileModifyTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.modify"; }

    @Override
    public String getName() { return "modify_file"; }

    @Override
    public String getDescription() { return "修改文件内容，支持替换、插入、删除行"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_MODIFY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "文件路径"),
                "operation", Map.of("type", "string", "description", "操作类型: replace / insert / delete / append（追加到末尾）"),
                "startLine", Map.of("type", "integer", "description", "起始行号"),
                "endLine", Map.of("type", "integer", "description", "结束行号（replace操作）"),
                "content", Map.of("type", "string", "description", "新内容")
            ),
            "required", new String[]{"path", "operation"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String operation = getRequiredStringArg(arguments, "operation");
            
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                return error("文件不存在: " + path);
            }

            List<String> lines = readTextLines(filePath);
            int startLine = arguments.containsKey("startLine") ? 
                ((Number) arguments.get("startLine")).intValue() : 0;
            String content = getStringArg(arguments, "content", "");

            switch (operation) {
                case "replace" -> {
                    int endLine = arguments.containsKey("endLine") ? 
                        ((Number) arguments.get("endLine")).intValue() : startLine;
                    if (startLine < 1 || startLine > lines.size()) {
                        return error("行号超出范围: " + startLine);
                    }
                    // 替换指定行范围
                    for (int i = endLine; i >= startLine; i--) {
                        lines.remove(i - 1);
                    }
                    String[] newLines = content.split("\n");
                    for (int i = 0; i < newLines.length; i++) {
                        lines.add(startLine - 1 + i, newLines[i]);
                    }
                }
                case "insert" -> {
                    if (startLine < 1 || startLine > lines.size() + 1) {
                        return error("插入位置超出范围: " + startLine + " (有效范围: 1-" + (lines.size() + 1) + ")");
                    }
                    String[] newLines = content.split("\n");
                    for (int i = 0; i < newLines.length; i++) {
                        lines.add(startLine - 1 + i, newLines[i]);
                    }
                }
                case "delete" -> {
                    int endLine = arguments.containsKey("endLine") ? 
                        ((Number) arguments.get("endLine")).intValue() : startLine;
                    if (startLine < 1 || startLine > lines.size()) {
                        return error("行号超出范围: " + startLine);
                    }
                    for (int i = endLine; i >= startLine; i--) {
                        lines.remove(i - 1);
                    }
                }
                case "append" -> {
                    // 实测模型会写 operation=append（追加内容），以前只回"未知操作类型: append"，
                    // 白跑一轮。追加到文件末尾本来就是 modify_file 该会的事。
                    String appendContent = getStringArg(arguments, "content", null);
                    if (appendContent == null) {
                        return error("append 操作需要 content 参数（要追加的内容）");
                    }
                    if (!lines.isEmpty()) {
                        lines.add("");
                    }
                    for (String line : appendContent.split("\\r?\\n", -1)) {
                        lines.add(line);
                    }
                }
                default -> {
                    return error("未知操作类型: " + operation
                        + "。支持 replace / insert / delete / append（想直接追加也可以用 append_file）");
                }
            }

            // 按文件原本的编码写回：GBK 的仍是 GBK，不会被悄悄改成 UTF-8
            Files.write(filePath, lines, charsetOf(filePath));
            return success("文件已修改: " + path + " (操作: " + operation + ")");

        } catch (IOException e) {
            return error("修改文件失败: " + e.getMessage());
        } catch (Exception e) {
            return error("参数错误: " + e.getMessage());
        }
    }
}
