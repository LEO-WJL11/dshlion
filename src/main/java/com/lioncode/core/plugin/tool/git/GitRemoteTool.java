package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git Remote管理工具
 */
@Component
public class GitRemoteTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.remote"; }
    @Override
    public String getName() { return "git_remote"; }
    @Override
    public String getDescription() { return "查看和管理Git远程仓库"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "仓库路径"),
            "action", Map.of("type", "string", "description", "list/add/remove/show")
        ), "required", new String[]{"path", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String action = getRequiredStringArg(arguments, "action");

            ProcessBuilder pb;
            switch (action) {
                case "list" -> pb = new ProcessBuilder("git", "remote", "-v");
                case "show" -> pb = new ProcessBuilder("git", "remote", "show", "origin");
                default -> { return error("仅支持list/show操作"); }
            }

            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return success(output.isEmpty() ? "（无远程仓库）" : output);
        } catch (Exception e) {
            return error("Git remote操作失败: " + e.getMessage());
        }
    }
}
