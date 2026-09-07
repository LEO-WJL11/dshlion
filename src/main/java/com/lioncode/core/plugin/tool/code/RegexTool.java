package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式测试工具
 */
@Component
public class RegexTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.regex"; }
    @Override
    public String getName() { return "regex_test"; }
    @Override
    public String getDescription() { return "测试正则表达式匹配"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "pattern", Map.of("type", "string", "description", "正则表达式"),
            "input", Map.of("type", "string", "description", "测试文本"),
            "flags", Map.of("type", "string", "description", "标志：i=忽略大小写", "default", "")
        ), "required", new String[]{"pattern", "input"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String pattern = getRequiredStringArg(arguments, "pattern");
            String input = getRequiredStringArg(arguments, "input");
            String flags = getStringArg(arguments, "flags", "");
            
            int flag = flags.contains("i") ? Pattern.CASE_INSENSITIVE : 0;
            Pattern p = Pattern.compile(pattern, flag);
            Matcher m = p.matcher(input);
            
            List<String> matches = new ArrayList<>();
            while (m.find()) {
                matches.add(String.format("位置[%d,%d]: '%s'", m.start(), m.end(), m.group()));
            }
            
            if (matches.isEmpty()) return success("无匹配结果");
            StringBuilder sb = new StringBuilder("匹配结果:\n");
            matches.forEach(match -> sb.append(match).append("\n"));
            return success(sb.toString());
        } catch (Exception e) {
            return error("正则表达式错误: " + e.getMessage());
        }
    }
}
