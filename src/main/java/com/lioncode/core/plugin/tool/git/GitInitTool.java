package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git初始化工具
 */
@Component
public class GitInitTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.init"; }
    @Override
    public String getName() { return "git_init"; }
    @Override
    public String getDescription() { return "初始化Git仓库"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "目录路径"),
            "bare", Map.of("type", "boolean", "description", "是否创建裸仓库", "default", false)
        ), "required", new String[]{"path"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            boolean bare = getBoolArg(arguments, "bare", false);
            // 【实测】模型会直接 git_init 到一个还不存在的目录（.git_test），
            // 结果 ProcessBuilder 报 error=267 目录名称无效，它还以为是"没装 git"。
            // git init 到新目录本来就是正常用法，这里直接建出来。
            Files.createDirectories(java.nio.file.Path.of(path));
            
            ProcessBuilder pb = bare ? 
                new ProcessBuilder(gitExecutable(), "init", "--bare") :
                new ProcessBuilder(gitExecutable(), "init");
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
            return success("Git仓库已初始化:\n" + output);
        } catch (Exception e) {
            return error("Git初始化失败: " + e.getMessage());
        }
    }
}
