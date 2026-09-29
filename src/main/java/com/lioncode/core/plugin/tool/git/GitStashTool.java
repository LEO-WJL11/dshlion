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
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return success(output.isEmpty() ? "操作完成" : output);
        } catch (Exception e) {
            return error("Git stash操作失败: " + e.getMessage());
        }
    }
}
