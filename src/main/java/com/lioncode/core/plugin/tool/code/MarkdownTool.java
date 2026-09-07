package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Markdown渲染工具
 */
@Component
public class MarkdownTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.markdown"; }
    @Override
    public String getName() { return "markdown_render"; }
    @Override
    public String getDescription() { return "Markdown转HTML预览"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "markdown", Map.of("type", "string", "description", "Markdown内容")
        ), "required", new String[]{"markdown"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String md = getRequiredStringArg(arguments, "markdown");
        // 简化实现：基本Markdown转换
        String html = md
            .replaceAll("^### (.*)$", "<h3>$1</h3>")
            .replaceAll("^## (.*)$", "<h2>$1</h2>")
            .replaceAll("^# (.*)$", "<h1>$1</h1>")
            .replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>")
            .replaceAll("\\*(.*?)\\*", "<em>$1</em>")
            .replaceAll("`([^`]+)`", "<code>$1</code>")
            .replaceAll("\n", "<br>");
        return success(html);
    }
}
