package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
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
            boolean bare = Boolean.TRUE.equals(arguments.get("bare"));
            
            ProcessBuilder pb = bare ? 
                new ProcessBuilder("git", "init", "--bare") :
                new ProcessBuilder("git", "init");
            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return success("Git仓库已初始化:\n" + output);
        } catch (Exception e) {
            return error("Git初始化失败: " + e.getMessage());
        }
    }
}
