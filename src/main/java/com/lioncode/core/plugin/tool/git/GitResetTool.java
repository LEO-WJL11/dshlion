package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * git reset 工具。
 *
 * <p>补这个工具的原因：用户实跑时模型明确调用了 {@code git_reset}，而我们没有
 * （只回了"未找到工具: git_reset"）。reset 是撤销暂存/回退提交的常用操作，
 * 缺了它模型只能用 execute_command 拼命令，容易写错。
 *
 * <p>危险度由审批策略决定（工具 id 是 {@code tool.git.reset}）：
 * {@code --hard} 会丢改动，这里在返回内容里也明确写出来。
 */
@Component
public class GitResetTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.reset"; }
    @Override
    public String getName() { return "git_reset"; }
    @Override
    public String getDescription() { return "撤销暂存/回退提交（--soft/--mixed/--hard，可选 ref）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "Git仓库路径"),
            "mode", Map.of("type", "string",
                "description", "soft（只移动 HEAD，改动留在暂存区）/ mixed（默认，改动留在工作区）/ hard（丢弃改动）",
                "default", "mixed"),
            "ref", Map.of("type", "string", "description", "回退到哪个提交，默认 HEAD")
        ), "required", new String[]{"path"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String dirError = checkGitDirectory(path);
            if (dirError != null) {
                return error(dirError);
            }
            String mode = getStringArg(arguments, "mode", "mixed").toLowerCase().trim();
            // 模型可能写成 --hard / -hard，统一成 hard
            mode = mode.replace("-", "");
            if (!List.of("soft", "mixed", "hard", "keep", "merge").contains(mode)) {
                return error("未知模式: " + mode + "。只能是 soft / mixed / hard");
            }
            String ref = getStringArg(arguments, "ref", "HEAD");
            if (ref == null || ref.isBlank()) {
                ref = "HEAD";
            }

            List<String> cmd = new ArrayList<>(List.of(gitExecutable(), "reset", "--" + mode));
            if (!"HEAD".equals(ref)) {
                cmd.add(ref);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return error("git reset 超时");
            }
            int code = process.exitValue();
            String head = "git reset --" + mode + (("HEAD".equals(ref)) ? "" : " " + ref)
                + "（退出码 " + code + "）";
            if (code != 0) {
                return error(head + "\n" + output + gitHint(new RuntimeException(output)));
            }
            String warn = "hard".equals(mode) ? "\n注意：--hard 已丢弃工作区改动。" : "";
            return success(head + "\n" + (output.isBlank() ? "（无输出）" : output) + warn);
        } catch (Exception e) {
            return error("git reset 失败: " + e.getMessage() + gitHint(e));
        }
    }
}
