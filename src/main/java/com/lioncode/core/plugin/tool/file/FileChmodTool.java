package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文件权限修改工具
 */
@Component
public class FileChmodTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.file.chmod"; }
    @Override
    public String getName() { return "change_permissions"; }
    @Override
    public String getDescription() { return "修改文件权限"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_MODIFY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "文件路径"),
            "readable", Map.of("type", "boolean", "description", "可读"),
            "writable", Map.of("type", "boolean", "description", "可写"),
            "executable", Map.of("type", "boolean", "description", "可执行")
        ), "required", new String[]{"path"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            Path filePath = Path.of(path);
            
            boolean readable = getBoolArg(arguments, "readable", false);
            boolean writable = getBoolArg(arguments, "writable", false);
            boolean executable = getBoolArg(arguments, "executable", false);
            
            // 使用Files.setPosixFilePermissions在支持的系统上
            Set<PosixFilePermission> perms = new HashSet<>();
            if (readable) perms.add(PosixFilePermission.OWNER_READ);
            if (writable) perms.add(PosixFilePermission.OWNER_WRITE);
            if (executable) perms.add(PosixFilePermission.OWNER_EXECUTE);
            
            try {
                Files.setPosixFilePermissions(filePath, perms);
            } catch (UnsupportedOperationException e) {
                // Windows系统不支持POSIX权限
                filePath.toFile().setReadable(readable);
                filePath.toFile().setWritable(writable);
                filePath.toFile().setExecutable(executable);
            }
            
            return success("权限已修改: " + path);
        } catch (Exception e) {
            return error("修改权限失败: " + e.getMessage());
        }
    }
}
