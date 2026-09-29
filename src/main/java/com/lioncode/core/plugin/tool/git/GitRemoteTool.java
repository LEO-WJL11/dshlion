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
            "action", Map.of("type", "string", "description", "list/add/remove/show"),
            "name", Map.of("type", "string", "description", "远程名（add/remove/show 用，如 origin）"),
            "url", Map.of("type", "string", "description", "仓库地址（add 用）")
        ), "required", new String[]{"path", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String action = getRequiredStringArg(arguments, "action");

            ProcessBuilder pb;
            switch (action) {
                case "list", "ls" -> pb = new ProcessBuilder(gitExecutable(), "remote", "-v");
                case "show" -> {
                    String name = getStringArg(arguments, "name", "origin");
                    pb = new ProcessBuilder(gitExecutable(), "remote", "show",
                        name == null || name.isBlank() ? "origin" : name);
                }
                // 实测模型想 add（"仅支持list/show操作"直接卡住它）。加远程是很常用的操作，
                // 补上 add/remove；报错里也把支持的操作列全。
                case "add" -> {
                    String name = getStringArg(arguments, "name", null);
                    String url = getStringArg(arguments, "url", null);
                    if (name == null || name.isBlank() || url == null || url.isBlank()) {
                        return error("add 操作需要 name（远程名，如 origin）和 url（仓库地址）两个参数");
                    }
                    pb = new ProcessBuilder(gitExecutable(), "remote", "add", name, url);
                }
                case "remove", "rm", "delete" -> {
                    String name = getStringArg(arguments, "name", null);
                    if (name == null || name.isBlank()) {
                        return error("remove 操作需要 name（要删掉的远程名）");
                    }
                    pb = new ProcessBuilder(gitExecutable(), "remote", "remove", name);
                }
                default -> {
                    return error("未知操作: " + action
                        + "。支持 list / show / add / remove（add 需要 name 和 url）");
                }
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
