package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git日志查看工具
 */
@Component
public class GitLogTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.log"; }

    @Override
    public String getName() { return "git_log"; }

    @Override
    public String getDescription() { return "查看Git提交历史"; }

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
                "count", Map.of("type", "integer", "description", "显示条数", "default", 20)
            ),
            "required", new String[]{"path"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            int count = arguments.containsKey("count") ? 
                ((Number) arguments.get("count")).intValue() : 20;

            ProcessBuilder pb = new ProcessBuilder(
                "git", "log", "--oneline", "-" + count);
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

            return success(output.isEmpty() ? "（无提交记录）" : output);

        } catch (Exception e) {
            return error("Git日志获取失败: " + e.getMessage());
        }
    }
}
