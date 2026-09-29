package com.lioncode.core.plugin.tool.code;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 进制转换工具。
 *
 * <p>实测两次失败（用户日志）：
 * <pre>
 *   number_convert(value=255, fromBase=dec, toBase=hex)  ❌ ClassCastException
 * </pre>
 * 模型给的是**进制名**（dec/hex），工具却直接
 * {@code ((Number) arguments.get("fromBase")).intValue()}。1.1.9 加的统一类型转换只认
 * schema 里声明为 integer 的"数字字符串"（"10"），"dec" 这种转不了，于是照样炸
 * —— 所以这里自己认进制名，并且不再硬转 Number。
 */
@Component
public class NumberTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.code.number"; }
    @Override
    public String getName() { return "number_convert"; }
    @Override
    public String getDescription() { return "进制转换（dec/hex/bin/oct 或 10/16/2/8）"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "value", Map.of("type", "string", "description", "要转换的数值，如 255"),
            "fromBase", Map.of("type", "string",
                "description", "源进制：10/16/2/8，也认 dec/hex/bin/oct", "default", "10"),
            "toBase", Map.of("type", "string",
                "description", "目标进制：10/16/2/8，也认 dec/hex/bin/oct", "default", "16")
        ), "required", new String[]{"value", "fromBase", "toBase"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String value = getRequiredStringArg(arguments, "value").trim();
            int fromBase = parseBase(arguments.get("fromBase"), 10);
            int toBase = parseBase(arguments.get("toBase"), 16);
            if (fromBase < 2 || fromBase > 36 || toBase < 2 || toBase > 36) {
                return error("进制必须在 2-36 之间（收到 fromBase=" + arguments.get("fromBase")
                    + ", toBase=" + arguments.get("toBase") + "）；也可以直接写 dec/hex/bin/oct");
            }

            String v = value;
            boolean negative = v.startsWith("-");
            if (negative || v.startsWith("+")) {
                v = v.substring(1);
            }
            if (v.startsWith("0x") || v.startsWith("0X")) {
                v = v.substring(2);
                fromBase = 16;
            } else if (v.startsWith("0b") || v.startsWith("0B")) {
                v = v.substring(2);
                fromBase = 2;
            } else if (v.startsWith("0o") || v.startsWith("0O")) {
                v = v.substring(2);
                fromBase = 8;
            }

            long decimal = Long.parseLong(v, fromBase);
            if (negative) {
                decimal = -decimal;
            }
            String out = Long.toString(decimal, toBase).toUpperCase();
            return success(value + "（" + baseName(fromBase) + "） = " + out + "（" + baseName(toBase)
                + "）\n十进制: " + decimal);
        } catch (NumberFormatException e) {
            return error("转换失败: " + arguments.get("value") + " 不是合法的 "
                + arguments.get("fromBase") + " 进制数字");
        } catch (Exception e) {
            return error("转换失败: " + e.getMessage());
        }
    }

    /** 认 "10" / "dec" / "hex" / "bin" / "oct" / "decimal" / "十进制"… */
    static int parseBase(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = String.valueOf(raw).trim().toLowerCase();
        if (s.isEmpty()) {
            return fallback;
        }
        switch (s) {
            case "dec", "decimal", "denary", "10进制", "十进制" -> { return 10; }
            case "hex", "hexadecimal", "16进制", "十六进制" -> { return 16; }
            case "bin", "binary", "2进制", "二进制" -> { return 2; }
            case "oct", "octal", "8进制", "八进制" -> { return 8; }
            default -> { /* 继续按数字解析 */ }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String baseName(int base) {
        return switch (base) {
            case 2 -> "2进制";
            case 8 -> "8进制";
            case 10 -> "10进制";
            case 16 -> "16进制";
            default -> base + "进制";
        };
    }
}
