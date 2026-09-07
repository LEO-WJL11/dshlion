package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 文件信息查看工具
 */
@Component
public class FileInfoTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.info"; }
    @Override
    public String getName() { return "file_info"; }
    @Override
    public String getDescription() { return "查看文件详细信息（大小、修改时间等）"; }
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

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) return error("路径不存在: " + path);
            
            var attrs = Files.readAttributes(filePath, "*");
            long size = Files.size(filePath);
            var modified = Files.getLastModifiedTime(filePath);
            boolean isDir = Files.isDirectory(filePath);
            boolean isFile = Files.isRegularFile(filePath);
            
            return success(String.format(
                "路径: %s\n类型: %s\n大小: %d 字节\n最后修改: %s\n可读: %s\n可写: %s",
                path, isDir ? "目录" : "文件", size, modified, 
                Files.isReadable(filePath), Files.isWritable(filePath)));
        } catch (Exception e) {
            return error("获取文件信息失败: " + e.getMessage());
        }
    }
}
