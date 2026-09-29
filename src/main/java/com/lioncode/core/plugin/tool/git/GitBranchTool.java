package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git分支管理工具
 */
@Component
public class GitBranchTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.branch"; }
    @Override
    public String getName() { return "git_branch"; }
    @Override
    public String getDescription() { return "查看、创建、切换Git分支"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "仓库路径"),
            "action", Map.of("type", "string", "description", "list/create/checkout/delete"),
            "branch", Map.of("type", "string", "description", "分支名")
        ), "required", new String[]{"path", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String action = getRequiredStringArg(arguments, "action");
            String branch = getStringArg(arguments, "branch", null);

            ProcessBuilder pb;
            switch (action) {
                case "list" -> pb = new ProcessBuilder(gitExecutable(), "branch", "-a");
                case "create" -> pb = new ProcessBuilder(gitExecutable(), "branch", branch);
                case "checkout" -> pb = new ProcessBuilder(gitExecutable(), "checkout", branch);
                case "delete" -> pb = new ProcessBuilder(gitExecutable(), "branch", "-d", branch);
                default -> { return error("未知操作: " + action); }
            }

            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            gitEnv(pb);
            Process process = pb.start();
            // 【顺序要紧】先等进程结束再读：readAllBytes() 会一直阻塞到 stdout 关闭，
            // 而 git 在等凭据/网络时不会关（实测卡了 3 分钟），先读就等于没有超时。
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return error("git 命令超时（30 秒没返回）：多半在等网络或凭据。"
                    + "远程操作用 -n 只看本地配置，或先确认网络/凭据。");
            }
            String output = new String(process.getInputStream().readAllBytes());
            return success(output.isEmpty() ? "操作完成" : output);
        } catch (Exception e) {
            return error("Git分支操作失败: " + e.getMessage());
        }
    }
}
