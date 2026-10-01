package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.agent.change.ChangeReview;

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

    /** 改动人工审核闸门：开着的话，改文件先攒成待审改动，人点了通过才落盘 */
    private final ChangeReview changeReview;

    public FileModifyTool(ChangeReview changeReview) {
        this.changeReview = changeReview;
    }

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
                "operation", Map.of("type", "string", "description", "操作类型: replace / insert / delete / append（追加到末尾）/ create（新建或整体覆盖）"),
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
            String operationLow = operation.trim().toLowerCase();
            if (!Files.exists(filePath)) {
                // 【实测】模型会把 modify_file 当"建文件"用（operation=create），
                // 以前只回一句"文件不存在"，白跑一轮。既然它想要的语义就是"把文件弄成我要的样"，
                // 这里直接按 create_file 建出来，并把话说明白。
                if (operationLow.equals("create") || operationLow.equals("write")
                        || operationLow.equals("new") || operationLow.equals("touch")) {
                    Path parent = filePath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
            java.util.Optional<ToolResult> gate = changeReview.intercept(
                getName(), path, null, getStringArg(arguments, "content", ""));
            if (gate.isPresent()) {
                return gate.get();
            }
                    Files.writeString(filePath, getStringArg(arguments, "content", ""),
                        java.nio.charset.StandardCharsets.UTF_8);
                    return success("文件不存在，已按 create 语义新建: " + path
                        + "（新建文件也可以直接用 create_file；改已有文件用 replace/append）");
                }
                return error("文件不存在: " + path + "（想新建：operation=create 并带上 content，"
                    + "或直接用 create_file / write_file）");
            }

            List<String> lines = readTextLines(filePath);
            // 留一份原文：审核要拿它和"改完之后"比对（lines 后面会被就地改）
            String originalFull = String.join("\n", lines);
            int startLine = getIntArg(arguments, "startLine", 0);
            String content = getStringArg(arguments, "content", "");

            switch (operationLow) {
                case "create", "write", "new", "touch" -> {
                    // 文件已经在了：create 没法"再建一次"，直接把它要的内容当作整体覆盖，
                    // 免得模型在 create/replace 之间来回试。
            java.util.Optional<ToolResult> gate = changeReview.intercept(
                getName(), path, String.join("\n", lines), content);
            if (gate.isPresent()) {
                return gate.get();
            }
                    Files.writeString(filePath, content, charsetOf(filePath));
                    return success("文件已存在，已按 create 覆盖写入: " + path);
                }
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
                        boolean all = getBoolArg(arguments, "all", false);
                        String replaced = all
                            ? full.replace(oldText, content)
                            : full.replaceFirst(java.util.regex.Pattern.quote(oldText),
                                java.util.regex.Matcher.quoteReplacement(content));
            java.util.Optional<ToolResult> gate = changeReview.intercept(
                getName(), path, full, replaced);
            if (gate.isPresent()) {
                return gate.get();
            }
                        writeTextPreservingCharset(filePath, replaced);
                        int times = all ? countOccurrences(full, oldText) : 1;
                        return success("已替换 " + times + " 处（按原文匹配）：" + path);
                    }
                    // ② 没给 oldText 就得给行号；这里把话说清楚，别只回一句"行号超出范围: 0"
                    if (startLine < 1) {
                        return error("replace 需要行号或原文：给 startLine（可加 endLine），"
                            + "或者给 oldText + content 让我按原文替换");
                    }
                    int endLine = getIntArg(arguments, "endLine", startLine);
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
                    int endLine = getIntArg(arguments, "endLine", startLine);
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

            java.util.Optional<ToolResult> gate = changeReview.intercept(
                getName(), path, originalFull, String.join("\n", lines));
            if (gate.isPresent()) {
                return gate.get();
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
