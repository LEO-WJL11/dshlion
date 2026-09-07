package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 文本差异比较工具
 */
@Component
public class DiffTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.diff"; }
    @Override
    public String getName() { return "diff_text"; }
    @Override
    public String getDescription() { return "比较两段文本的差异"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "text1", Map.of("type", "string", "description", "文本1"),
            "text2", Map.of("type", "string", "description", "文本2")
        ), "required", new String[]{"text1", "text2"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String text1 = getRequiredStringArg(arguments, "text1");
        String text2 = getRequiredStringArg(arguments, "text2");
        
        String[] lines1 = text1.split("\n");
        String[] lines2 = text2.split("\n");
        
        StringBuilder sb = new StringBuilder();
        int maxLines = Math.max(lines1.length, lines2.length);
        
        for (int i = 0; i < maxLines; i++) {
            String l1 = i < lines1.length ? lines1[i] : "";
            String l2 = i < lines2.length ? lines2[i] : "";
            
            if (!l1.equals(l2)) {
                sb.append("行").append(i + 1).append(":\n");
                sb.append("  - ").append(l1).append("\n");
                sb.append("  + ").append(l2).append("\n");
            }
        }
        
        return success(sb.length() > 0 ? sb.toString() : "文本完全相同");
    }
}
