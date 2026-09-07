package com.lioncode.core.plugin.tool.web;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 翻译工具（占位实现）
 */
@Component
public class TranslateTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.web.translate"; }
    @Override
    public String getName() { return "translate"; }
    @Override
    public String getDescription() { return "文本翻译（需配置翻译API）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "text", Map.of("type", "string", "description", "待翻译文本"),
            "from", Map.of("type", "string", "description", "源语言", "default", "auto"),
            "to", Map.of("type", "string", "description", "目标语言", "default", "zh")
        ), "required", new String[]{"text", "to"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String text = getRequiredStringArg(arguments, "text");
        String to = getRequiredStringArg(arguments, "to");
        return success("翻译功能需要配置翻译API密钥。\n原文: " + text + "\n目标语言: " + to);
    }
}
