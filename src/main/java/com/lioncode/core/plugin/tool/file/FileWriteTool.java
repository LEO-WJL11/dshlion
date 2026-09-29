package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件写入工具
 * 
 * 创建或覆盖写入指定路径的文件内容。
 */
@Component
public class FileWriteTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    @Override
    public String getId() { return "tool.file.write"; }

    @Override
    public String getName() { return "write_file"; }

    @Override
    public String getDescription() { return "创建或覆盖写入指定路径的文件内容"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_MODIFY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "文件路径"),
                "content", Map.of("type", "string", "description", "文件内容")
            ),
            "required", new String[]{"path", "content"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String content = getRequiredStringArg(arguments, "content");

            Path filePath = Path.of(path);
            // 自动创建父目录
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }

            // 覆盖已有文件时保留它原来的编码（新文件用 UTF-8）
            Files.writeString(filePath, content, charsetOf(filePath));
            log.debug("写入文件: {} ({}字节)", path, content.length());
            return success("文件已写入: " + path + " (" + content.length() + "字节)");

        } catch (IOException e) {
            return error("写入文件失败: " + e.getMessage());
        } catch (Exception e) {
            return error("参数错误: " + e.getMessage());
        }
    }
}
