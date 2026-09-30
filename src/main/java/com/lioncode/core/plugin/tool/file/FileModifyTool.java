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
                "content", Map.of("type", "string", "description", "新内容"),
                "oldText", Map.of("type", "string",
                    "description", "replace 用：要替换掉的原文（给了它就不用行号）"),
                "all", Map.of("type", "boolean",
                    "description", "replace 用：是否替换所有出现（默认只替换第一处）")
            ),
            "required", new String[]{"path", "operation"}
        );
    }

    /** 按原文替换时数一下替换了几处 */
    private int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
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
                    // ① 给了 oldText 就按文本替换（最省事，也不用数行号 ——
                    //    实测模型经常只给 content 不给行号，以前直接报"行号超出范围: 0"）。
                    String oldText = getStringArg(arguments, "oldText", null);
                    if (oldText != null && !oldText.isBlank()) {
                        String full = String.join("\n", lines);
                        if (!full.contains(oldText)) {
                            return error("要替换的原文没找到（oldText 要和文件里一字不差，"
                                + "先用 read_file 看一眼）：" + oldText.substring(0,
                                    Math.min(60, oldText.length())));
                        }
                        boolean all = Boolean.TRUE.equals(arguments.get("all"));
                        String replaced = all
                            ? full.replace(oldText, content)
                            : full.replaceFirst(java.util.regex.Pattern.quote(oldText),
                                java.util.regex.Matcher.quoteReplacement(content));
                        writeTextPreservingCharset(filePath, replaced);
                        int times = all ? countOccurrences(full, oldText) : 1;
                        return success("已替换 " + times + " 处（按原文匹配）：" + path);
                    }
                    // ② 没给 oldText 就得给行号；这里把话说清楚，别只回一句"行号超出范围: 0"
                    if (startLine < 1) {
                        return error("replace 需要行号或原文：给 startLine（可加 endLine），"
                            + "或者给 oldText + content 让我按原文替换");
                    }
                    int endLine = arguments.containsKey("endLine") ? 
                        ((Number) arguments.get("endLine")).intValue() : startLine;
                    if (startLine > lines.size()) {
                        return error("起始行号超出范围: " + startLine + "（这个文件只有 "
                            + lines.size() + " 行）");
                    }
                    endLine = Math.min(endLine, lines.size());
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
                    if (startLine < 1) {
                        return error("insert 需要在第几行插入：给 startLine（1 = 文件开头，"
                            + (lines.size() + 1) + " = 文件末尾）");
                    }
                    if (startLine > lines.size() + 1) {
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
