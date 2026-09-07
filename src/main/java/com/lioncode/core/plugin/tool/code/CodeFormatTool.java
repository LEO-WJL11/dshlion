package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 代码格式化工具
 */
@Component
public class CodeFormatTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.format"; }
    @Override
    public String getName() { return "format_code"; }
    @Override
    public String getDescription() { return "格式化代码（缩进、换行等）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "code", Map.of("type", "string", "description", "代码内容"),
            "language", Map.of("type", "string", "description", "语言类型")
        ), "required", new String[]{"code"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String code = getRequiredStringArg(arguments, "code");
        // 简化实现：基本的缩进修复
        String formatted = code.replaceAll("\t", "    ");
        return success(formatted);
    }
}
