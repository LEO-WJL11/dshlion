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
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                                // 单个删不掉就跳过，最后统一看是否还在
                            }
                        });
                    }
                    if (Files.exists(target)) {
                        return error("删除失败（部分内容删不掉）: " + path);
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
}
