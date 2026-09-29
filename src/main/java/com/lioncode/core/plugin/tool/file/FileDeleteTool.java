package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件删除工具
 */
@Component
public class FileDeleteTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(FileDeleteTool.class);

    @Override
    public String getId() { return "tool.file.delete"; }

    @Override
    public String getName() { return "delete_file"; }

    @Override
    public String getDescription() { return "删除指定路径的文件或空目录"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_OPERATION; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "文件或目录路径"),
                "recursive", Map.of("type", "boolean", "default", false,
                    "description", "删目录时是否连内容一起删（默认 false，只删空目录）")
            ),
            "required", new String[]{"path"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            Path target = Path.of(path);

            // 【安全护栏】不许删工作区根目录（或它的上级）。
            // 实测：模型跑"把工具都用一遍，最后清理"时直接
            // delete_file(path=<工作区根>, recursive=true) —— 那是要删掉用户整个工作区，
            // 而且删到一半失败，留下一堆半删状态的文件。这种请求直接拒绝。
            if (isWorkspaceRootOrAbove(target)) {
                return error("拒绝删除当前工作区根目录: " + path
                    + "。删这里的文件请指定具体子路径（例如 " + path + "\\\\某个文件.txt），"
                    + "整体清理请用 execute_command 里明确写出要删的目录。");
            }

            if (!Files.exists(target)) {
                return error("路径不存在: " + path);
            }

            if (Files.isDirectory(target)) {
                // 【实测】模型想清掉整棵目录树（比如它自己建的 .git_test）时会撞上
                // "目录不为空，无法删除"。给它一个 recursive 开关，显式要求才递归删。
                boolean recursive = Boolean.TRUE.equals(arguments.get("recursive"))
                    || "true".equalsIgnoreCase(String.valueOf(arguments.get("recursive")));
                if (recursive) {
                    try (var walk = Files.walk(target)) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                            // Windows 上 .git 里的对象/索引是只读的，直接删会失败
                            // （实测：删含 .git 的目录报"部分内容删不掉"）。先清只读再删一次。
                            try {
                                Files.delete(p);
                            } catch (IOException first) {
                                try {
                                    p.toFile().setWritable(true);
                                    Files.delete(p);
                                } catch (IOException ignored) {
                                    // 还是删不掉就跳过，最后统一看是否还在
                                }
                            }
                        });
                    }
                    if (Files.exists(target)) {
                        // Java 逐文件删在 Windows 上偶尔还是删不干净（只读/长路径/占用），
                        // 退回系统的 rmdir /s /q —— 它对只读和长路径都比 Files.delete 宽。
                        if (isWindows() && removeWithCmd(target)) {
                            return success("已递归删除目录（走系统 rmdir）: " + path);
                        }
                        return error("删除失败（部分内容删不掉，可能被占用或权限不足）: " + path
                            + "。可以改用 execute_command 跑 Remove-Item -Recurse -Force。");
                    }
                    return success("已递归删除目录: " + path);
                }
                // 只删除空目录
                try (var stream = Files.list(target)) {
                    if (stream.findFirst().isPresent()) {
                        return error("目录不为空: " + path
                            + "（要连内容一起删就加 recursive=true；只是想删它里面的文件就先 delete_file 那些文件）");
                    }
                }
                Files.delete(target);
            } else {
                Files.delete(target);
            }

            log.debug("删除: {}", path);
            return success("已删除: " + path);

        } catch (IOException e) {
            return error("删除失败: " + e.getMessage());
        } catch (Exception e) {
            return error("参数错误: " + e.getMessage());
        }
    }

    /** 这个路径是不是当前工作区根目录（或它的上级）。 */
    private boolean isWorkspaceRootOrAbove(java.nio.file.Path target) {
        String ws = currentWorkspace();
        if (ws == null || ws.isBlank()) {
            return false;
        }
        try {
            java.nio.file.Path root = java.nio.file.Path.of(ws).toAbsolutePath().normalize();
            java.nio.file.Path t = target.toAbsolutePath().normalize();
            return t.equals(root) || root.startsWith(t);
        } catch (Exception e) {
            return false;
        }
    }

    /** Windows 上退回系统的 rmdir /s /q（下面这种删法对只读/长路径更宽）。 */
    private boolean removeWithCmd(java.nio.file.Path target) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "rmdir", "/s", "/q", target.toString())
                .redirectErrorStream(true).start();
            boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            return done && p.exitValue() == 0 && !java.nio.file.Files.exists(target);
        } catch (Exception e) {
            return false;
        }
    }
}
