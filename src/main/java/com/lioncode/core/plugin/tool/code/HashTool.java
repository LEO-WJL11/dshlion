package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * 哈希计算工具
 */
@Component
public class HashTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.hash"; }
    @Override
    public String getName() { return "hash"; }
    @Override
    public String getDescription() { return "计算字符串的MD5/SHA1/SHA256哈希值"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "输入内容"),
            "algorithm", Map.of("type", "string", "description", "算法：MD5/SHA-1/SHA-256", "default", "SHA-256")
        ), "required", new String[]{"input"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String input = getRequiredStringArg(arguments, "input");
            String algorithm = getStringArg(arguments, "algorithm", "SHA-256");
            
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hash = md.digest(input.getBytes());
            String hex = HexFormat.of().formatHex(hash);
            return success(algorithm + ": " + hex);
        } catch (Exception e) {
            return error("哈希计算失败: " + e.getMessage());
        }
    }
}
