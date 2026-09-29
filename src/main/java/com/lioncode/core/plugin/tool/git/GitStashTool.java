package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git Stash工具
 */
@Component
public class GitStashTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.stash"; }
    @Override
    public String getName() { return "git_stash"; }
    @Override
    public String getDescription() { return "Git stash操作（保存/恢复/列出/删除）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "仓库路径"),
            "action", Map.of("type", "string", "description", "push/pop/list/drop/apply（save 等于 push）"),
            "message", Map.of("type", "string", "description", "stash消息")
        ), "required", new String[]{"path", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String action = getRequiredStringArg(arguments, "action").toLowerCase().trim();
            // 别名：模型写 save / stash / create 的时候，意思都是 push（存起来）
            action = switch (action) {
                case "save", "stash", "create", "store", "push_stash" -> "push";
                case "restore", "unstash", "pop_stash" -> "pop";
                case "ls", "show", "status" -> "list";
                default -> action;
            };
            String message = getStringArg(arguments, "message", null);

            ProcessBuilder pb;
            switch (action) {
                case "push" -> {
                    pb = message != null ? 
                        new ProcessBuilder(gitExecutable(), "stash", "push", "-m", message) :
                        new ProcessBuilder(gitExecutable(), "stash", "push");
                }
                case "pop" -> pb = new ProcessBuilder(gitExecutable(), "stash", "pop");
                case "list" -> pb = new ProcessBuilder(gitExecutable(), "stash", "list");
                case "drop" -> pb = new ProcessBuilder(gitExecutable(), "stash", "drop");
                case "apply" -> pb = new ProcessBuilder(gitExecutable(), "stash", "apply");
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
            return error("Git stash操作失败: " + e.getMessage());
        }
    }
}
