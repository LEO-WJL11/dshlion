package com.lioncode.core.plugin.tool.file;

import com.lioncode.core.agent.change.ChangeReview;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.Map;

/**
 * 文件追加工具
 */
@Component
public class FileAppendTool extends AbstractToolPlugin {

    /** 改动人工审核闸门：开着的活，改文件先攒成待审改动，人点了通过才落盘 */
    private final ChangeReview changeReview;

    public FileAppendTool(ChangeReview changeReview) {
        this.changeReview = changeReview;
    }

    @Override
    public String getId() { return "tool.file.append"; }
    @Override
    public String getName() { return "append_file"; }
    @Override
    public String getDescription() { return "向文件末尾追加内容"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.FILE_MODIFY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "文件路径"),
            "content", Map.of("type", "string", "description", "追加内容")
        ), "required", new String[]{"path", "content"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String content = getRequiredStringArg(arguments, "content");
            // 追加也用文件原本的编码：GBK 的中文文件追加之后仍是 GBK，不会变成混合编码
            Path appendTarget = Path.of(path);
            String appendOld = Files.isRegularFile(appendTarget) ? readTextFile(appendTarget) : null;
            java.util.Optional<ToolResult> gate = changeReview.intercept(
                getName(), path, appendOld, appendOld == null ? content : appendOld + content);
            if (gate.isPresent()) {
                return gate.get();
            }

            Files.writeString(Path.of(path), content, charsetOf(Path.of(path)),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return success("内容已追加到: " + path);
        } catch (Exception e) {
            return error("追加失败: " + e.getMessage());
        }
    }
}
