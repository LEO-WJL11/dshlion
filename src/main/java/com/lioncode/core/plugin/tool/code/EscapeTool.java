package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字符串转义工具。
 *
 * <p>这一版按用户实测改了两处（真机日志）：
 * <pre>
 *   escape_string(action=escape, input=test, target=C:\Users\Leo\Desktop\测试)  ❌ 未知目标
 *   escape_string(action=escape, input=test, target=a"b'c)                     ❌ 未知目标
 * </pre>
 * 模型把**要转义的文本**放进了 {@code target}，却把 {@code input} 随手写成 "test" ——
 * 因为 target 这个词太像"目标字符串"了。所以：
 * <ol>
 *   <li>{@code input} 支持别名 text/value/data/string/content/source；</li>
 *   <li>识别得出"目标名写在 input 里"时自动互换；</li>
 *   <li>目标名不认识时不再报一句看不懂的"未知目标"，而是按 html 处理并在正文里说明；</li>
 *   <li>目标补上 json / regex / shell / xml（模型很爱要这几个）。</li>
 * </ol>
 */
@Component
public class EscapeTool extends AbstractToolPlugin {

    private static final List<String> TARGETS =
        List.of("html", "xml", "java", "json", "url", "regex", "shell");

    @Override
    public String getId() { return "tool.code.escape"; }
    @Override
    public String getName() { return "escape_string"; }
    @Override
    public String getDescription() { return "字符串转义/反转义（html/json/java/url/regex/shell）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "input", Map.of("type", "string", "description", "要转义的文本（放这里！不是 target）"),
            "action", Map.of("type", "string", "description", "escape / unescape"),
            "target", Map.of("type", "string",
                "description", "转到哪种格式：html / xml / java / json / url / regex / shell，默认 html",
                "default", "html")
        ), "required", new String[]{"input", "action"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> a = new LinkedHashMap<>(arguments);
        // input 的别名：模型常写 text / value / data / string / content
        for (String alias : new String[]{"text", "value", "data", "string", "content", "source", "str"}) {
            if (!a.containsKey("input") && a.get(alias) != null) {
                a.put("input", a.get(alias));
            }
        }

        String action = String.valueOf(a.get("action") == null ? "escape" : a.get("action"))
            .toLowerCase().trim();
        if (action.startsWith("un")) {
            action = "unescape";
        } else if (!"escape".equals(action)) {
            action = "escape";
        }

        String target = String.valueOf(a.get("target") == null ? "html" : a.get("target"))
            .toLowerCase().trim();
        String input = a.get("input") == null ? null : String.valueOf(a.get("input"));

        // 模型把目标名写进了 input、把正文写进了 target：换回来
        if (!TARGETS.contains(target) && input != null && TARGETS.contains(input.toLowerCase().trim())) {
            String swapped = input;
            input = target;
            target = swapped.toLowerCase().trim();
        }
        if (input == null || input.isBlank()) {
            return error("缺少必需参数: input" + requiredParamsHint());
        }

        String note = "";
        if (!TARGETS.contains(target)) {
            note = "（target 只能是 " + String.join(" / ", TARGETS)
                + " 之一，要转义的文本放在 input 里；本次按 html 处理）\n";
            target = "html";
        }
        return success(note + convert(target, action, input));
    }

    private String convert(String target, String action, String input) {
        boolean un = "unescape".equals(action);
        return switch (target) {
            case "html", "xml" -> un
                ? input.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                : input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            case "java", "json" -> un
                ? input.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
                : input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                       .replace("\r", "\\r").replace("\t", "\\t");
            case "url" -> {
                try {
                    yield un ? java.net.URLDecoder.decode(input, java.nio.charset.StandardCharsets.UTF_8)
                             : java.net.URLEncoder.encode(input, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    yield "URL 处理失败: " + e.getMessage();
                }
            }
            case "regex" -> un ? input : java.util.regex.Pattern.quote(input);
            case "shell" -> un
                ? input.replace("'\\''", "'")
                : "'" + input.replace("'", "'\\''") + "'";
            default -> input;
        };
    }
}
