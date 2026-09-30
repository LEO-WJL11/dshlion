package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件读取工具
 * 
 * 读取指定路径的文件内容，支持文本文件和二进制文件信息。
 */
@Component
public class FileReadTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    @Override
    public String getId() { return "tool.file.read"; }

    @Override
    public String getName() { return "read_file"; }

    @Override
    public String getDescription() { return "读取指定路径的文件内容"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "文件路径"),
                "encoding", Map.of("type", "string", "description", "文件编码", "default", "UTF-8"),
                "offset", Map.of("type", "integer", "description", "起始行号（从1开始）", "default", 1),
                "limit", Map.of("type", "integer", "description", "读取行数限制", "default", 1000)
            ),
            "required", new String[]{"path"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            int offset = getIntArg(arguments, "offset", 1);
            int limit = getIntArg(arguments, "limit", 1000);

            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                return error("文件不存在: " + path);
            }
            if (!Files.isRegularFile(filePath)) {
                return error("不是普通文件: " + path);
            }

            var lines = readTextLines(filePath);
            int start = Math.max(0, offset - 1);
            int end = Math.min(lines.size(), start + limit);
            
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%4d | ", i + 1)).append(lines.get(i)).append("\n");
            }

            String result = sb.toString();
            if (result.isEmpty()) {
                result = "（空文件）";
            }

            log.debug("读取文件: {} ({}行)", path, end - start);
            return success(result);

        } catch (IOException e) {
            return error("读取文件失败: " + e.getMessage());
        } catch (Exception e) {
            return error("参数错误: " + e.getMessage());
        }
    }
}
