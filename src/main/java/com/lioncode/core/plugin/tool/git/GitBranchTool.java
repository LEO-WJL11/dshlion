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
                case "list" -> pb = new ProcessBuilder("git", "branch", "-a");
                case "create" -> pb = new ProcessBuilder("git", "branch", branch);
                case "checkout" -> pb = new ProcessBuilder("git", "checkout", branch);
                case "delete" -> pb = new ProcessBuilder("git", "branch", "-d", branch);
                default -> { return error("未知操作: " + action); }
            }

            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return success(output.isEmpty() ? "操作完成" : output);
        } catch (Exception e) {
            return error("Git分支操作失败: " + e.getMessage());
        }
    }
}
