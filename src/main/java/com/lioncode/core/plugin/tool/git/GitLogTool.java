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
            int count = getIntArg(arguments, "count", 20);

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
            String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            int exit = process.exitValue();
            if (exit != 0) {
                // 【坑】以前不管退出码，直接把 git 的 fatal 当成功吐回去：
                // 空仓库会回一句 "fatal: your current branch 'master' does not have
                // any commits yet"，模型看不懂，就反复重试（用户那次连试了三次）。
                if (output.contains("does not have any commits") || output.contains("unknown revision")
                    || output.contains("bad default revision")) {
                    return success("（这个仓库还没有任何提交：先 create_file 建个文件，再 git_commit）");
                }
                if (output.contains("not a git repository")) {
                    return error("这不是 git 仓库：" + path + "（先 git_init，path 指向工作区目录）");
                }
                return error("git log 失败:\n" + output);
            }
            return success(output.isEmpty() ? "（无提交记录）" : output);

        } catch (Exception e) {
            return error("Git日志获取失败: " + e.getMessage());
        }
    }
}
