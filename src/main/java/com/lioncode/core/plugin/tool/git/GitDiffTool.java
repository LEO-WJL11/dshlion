package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git差异查看工具
 */
@Component
public class GitDiffTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.diff"; }

    @Override
    public String getName() { return "git_diff"; }

    @Override
    public String getDescription() { return "查看Git文件差异"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "Git仓库路径"),
                "file", Map.of("type", "string", "description", "指定文件（可选）"),
                "cached", Map.of("type", "boolean", "description", "查看暂存区差异", "default", false)
            ),
            "required", new String[]{"path"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String file = getStringArg(arguments, "file", null);
            boolean cached = Boolean.TRUE.equals(arguments.get("cached"));

            var cmd = new java.util.ArrayList<String>();
            cmd.add("git");
            cmd.add("diff");
            if (cached) cmd.add("--cached");
            if (file != null) cmd.add(file);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(path));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);

            return success(output.isEmpty() ? "（无差异）" : output);

        } catch (Exception e) {
            return error("Git差异获取失败: " + e.getMessage());
        }
    }
}
