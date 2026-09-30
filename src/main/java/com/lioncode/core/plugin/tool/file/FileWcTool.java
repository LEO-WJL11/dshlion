package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 文件统计工具（行数、字数、字节数）
 */
@Component
public class FileWcTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.wc"; }
    @Override
    public String getName() { return "word_count"; }
    @Override
    public String getDescription() { return "统计文件行数、字数、字节数"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "文件路径")
        ), "required", new String[]{"path"});
    }

    /** 目录累计统计：把能解码成文本的文件一个个加起来（二进制文件只算字节）。 */
    private String directoryStats(Path dir) {
        long files = 0, lines = 0, words = 0, bytes = 0, binary = 0;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                try {
                    long size = Files.size(p);
                    bytes += size;
                    files++;
                    String content = readTextFile(p);
                    lines += content.chars().filter(c -> c == '\n').count() + 1;
                    words += content.split("\\s+").length;
                } catch (Exception notText) {
                    binary++;
                }
            }
        } catch (Exception e) {
            return "统计目录失败: " + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("这是目录，已按**目录累计**统计: ").append(dir).append("\n");
        sb.append(String.format("文件数: %d\n行数: %d\n字数: %d\n字节数: %d", files, lines, words, bytes));
        if (binary > 0) {
            sb.append("\n（其中 ").append(binary).append(" 个不是能解码的文本，只算了字节数）");
        }
        return sb.toString();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            // 实测：模型把目录当文件传进来，原来只回"统计失败: <路径>"（异常 message 是空的），
            // 等于什么都没说，它下一轮才知道换文件。这里直接把原因写清楚。
            if (Files.isDirectory(Path.of(path))) {
                // 【实测】模型统计"工作区有多少字"时直接把目录传进来，原来只会回 ❌。
                // 它想要的就是"这个目录里有多少东西"，那就真的去数（只数能解码的文本文件）。
                return success(directoryStats(Path.of(path)));
            }
            if (!Files.exists(Path.of(path))) {
                return error("文件不存在: " + path);
            }
            // 实测：文件不是合法文本（损坏/二进制）时 readTextFile 会抛，整条就失败了。
            // 这种情况照样能给出有用的数字，只是标注一下"不是文本"。
            String content;
            boolean textOk = true;
            try {
                content = readTextFile(Path.of(path));
            } catch (Exception e) {
                textOk = false;
                content = new String(java.nio.file.Files.readAllBytes(Path.of(path)),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            long bytes = java.nio.file.Files.size(Path.of(path));
            if (!textOk) {
                return success(String.format("字节数: %d\n（这个文件不是能解码的文本，"
                    + "字符/行数没法准确统计；可以 file_info 看类型，或 read_file 看前面一段）", bytes));
            }
            long lines = content.chars().filter(c -> c == '\n').count() + 1;
            long words = content.split("\\s+").length;
            return success(String.format("行数: %d\n字数: %d\n字节数: %d", lines, words, bytes));
        } catch (Exception e) {
            return error("统计失败: " + e.getMessage());
        }
    }
}
