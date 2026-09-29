package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git提交工具
 */
@Component
public class GitCommitTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.commit"; }

    @Override
    public String getName() { return "git_commit"; }

    @Override
    public String getDescription() { return "Git暂存并提交更改"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "Git仓库路径"),
                "message", Map.of("type", "string", "description", "提交信息"),
                "addAll", Map.of("type", "boolean", "description", "是否暂存所有更改", "default", true)
            ),
            "required", new String[]{"path", "message"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String message = getRequiredStringArg(arguments, "message");
            boolean addAll = !arguments.containsKey("addAll") || 
                Boolean.TRUE.equals(arguments.get("addAll"));

            // 先执行git add
            if (addAll) {
                ProcessBuilder addPb = new ProcessBuilder(gitExecutable(), "add", "-A");
                addPb.directory(new File(path));
                addPb.start().waitFor(30, TimeUnit.SECONDS);
            }

            // 执行git commit
            // 新机器上没配 user.name/user.email 时 git commit 会直接失败
        // （"Please tell me who you are"）。带一组本地兜底身份，别让用户先手动配置。
        ProcessBuilder pb = new ProcessBuilder(gitExecutable(),
            "-c", "user.name=LionBox Agent",
            "-c", "user.email=agent@lionbox.local",
            "commit", "-m", message);
            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            gitEnv(pb);

            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return error("git 命令超时（30 秒没返回）：多半在等网络或凭据");
            }
            String output = new String(process.getInputStream().readAllBytes());


            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return success("提交成功:\n" + output);
            } else {
                return error("提交失败:\n" + output);
            }

        } catch (Exception e) {
            return error("Git提交失败: " + e.getMessage());
        }
    }
}
