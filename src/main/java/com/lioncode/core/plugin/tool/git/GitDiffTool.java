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
                "file", Map.of("type", "string", "description", "指定文件（可选，会自动放到 -- 后面）"),
                "rev", Map.of("type", "string", "description", "版本/范围（可选），如 HEAD~1、main..dev"),
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
            String rev = getStringArg(arguments, "rev", null);
            if (rev != null && !rev.isBlank()) {
                cmd.add(rev);                       // 例如 HEAD~1 或 main..dev
            }
            // 【坑】文件路径必须放在 `--` 后面。直接当位置参数传的话 git 会把它
            // 当成 revision 去解析，实测报 "fatal: ambiguous argument 'x.txt'"
            // —— 用户那次 git_diff 就是这么失败的。
            if (file != null && !file.isBlank()) {
                cmd.add("--");
                cmd.add(file);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
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

            return success(output.isEmpty() ? "（无差异）" : output);

        } catch (Exception e) {
            return error("Git差异获取失败: " + e.getMessage());
        }
    }
}
