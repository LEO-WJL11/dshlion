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
            String action = getRequiredStringArg(arguments, "action").trim().toLowerCase();
            String branch = getStringArg(arguments, "branch", null);

            String dirError = checkGitDirectory(path);
            if (dirError != null) {
                return error(dirError);
            }
            if (branch == null || branch.isBlank()) {
                branch = getStringArg(arguments, "name", null);
            }

            // 【实测】空仓库（还没有任何提交）里 `git branch foo` 只回
            // "fatal: not a valid object name: 'master'" —— master 还不存在，
            // 新分支没有可指向的提交。这不是 git 坏了，但话看不懂，用户以为是工具坏。
            // 空仓库下唯一有意义的做法是 `checkout -b`（建好并切过去），这里代它做掉。
            if (action.equals("create") || action.equals("checkout")) {
                if (branch == null || branch.isBlank()) {
                    return error("缺少分支名：action=" + action
                        + " 要带 branch 参数（例如 branch=dev）");
                }
                if (isEmptyRepository(path)) {
                    ProcessBuilder cb = new ProcessBuilder(gitExecutable(), "checkout", "-b", branch);
                    cb.directory(new File(path));
                    cb.redirectErrorStream(true);
                    gitEnv(cb);
                    Process cp = cb.start();
                    if (!cp.waitFor(30, TimeUnit.SECONDS)) {
                        cp.destroyForcibly();
                        return error("git 命令超时（30 秒没返回）");
                    }
                    String out = new String(cp.getInputStream().readAllBytes());
                    if (cp.exitValue() == 0) {
                        return success("仓库还没有任何提交，已直接创建并切换到分支 " + branch
                            + "（空仓库里 git branch 建不出分支，所以用 checkout -b；"
                            + "git_commit 一次之后再 git_branch list 就能看到它）\n" + out);
                    }
                    return error("创建分支失败（退出码 " + cp.exitValue() + "）:\n" + out);
                }
            }

            ProcessBuilder pb;
            switch (action) {
                case "list" -> pb = new ProcessBuilder(gitExecutable(), "branch", "-a");
                case "create" -> pb = new ProcessBuilder(gitExecutable(), "branch", branch);
                case "checkout" -> pb = new ProcessBuilder(gitExecutable(), "checkout", branch);
                case "delete" -> pb = new ProcessBuilder(gitExecutable(), "branch", "-d", branch);
                default -> {
                    return error("未知操作: " + action + "（支持 list / create / checkout / delete）");
                }
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
