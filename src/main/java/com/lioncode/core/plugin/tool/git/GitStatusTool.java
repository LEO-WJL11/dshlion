package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git状态查看工具
 */
@Component
public class GitStatusTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.status"; }

    @Override
    public String getName() { return "git_status"; }

    @Override
    public String getDescription() { return "查看Git仓库状态"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "Git仓库路径")
            ),
            "required", new String[]{"path"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return runGitCommand(arguments, gitExecutable(), "status", "--short");
    }

    protected ToolResult runGitCommand(Map<String, Object> arguments, String... command) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            gitEnv(pb);

            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return error("git 命令超时（30 秒没返回）：多半在等网络或凭据");
            }
            String output = new String(process.getInputStream().readAllBytes());


            return success(output.isEmpty() ? "（无变更）" : output);

        } catch (Exception e) {
            return error("Git命令执行失败: " + e.getMessage());
        }
    }
}
