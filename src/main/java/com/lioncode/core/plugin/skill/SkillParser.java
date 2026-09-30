package com.lioncode.core.plugin.skill;

import org.yaml.snakeyaml.Yaml;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SKILL.md 解析器：YAML frontmatter + Markdown 正文 → {@link SkillDefinition}。
 *
 * <p>格式（通用 skill 格式，和社区那套一致，别人写的技能拷进来就能用）：
 * <pre>
 * ---
 * name: pdf
 * display_name: PDF 处理
 * description: 一句话说明能力
 * when_to_use: 什么情况下该用
 * keywords: [pdf, 合并]
 * tools: [read_file, execute_command]
 * mode: standard
 * model: ""
 * ---
 * 正文：给模型的具体指令
 * </pre>
 *
 * <p>【为什么解析失败也要返回对象而不是抛异常】见 {@link SkillDefinition#error()}：
 * 用户手写 frontmatter 出错太正常了（少个冒号、中文冒号、列表没缩进），
 * 一个坏文件不能让应用起不来，也不能让别的技能跟着消失。</p>
 */
public final class SkillParser {

    /** 合法 id：字母数字和 . _ -（要和 {@code @skill:<id>} 的写法兼容，所以不放空格和中文）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-]*");

    /** frontmatter 里那些"必须有"的字段：缺了模型就没法判断要不要用这个技能。 */
    private static final List<String> REQUIRED = List.of("name", "display_name", "description", "when_to_use");

    private SkillParser() {
    }

    /**
     * 解析一份 SKILL.md。
     *
     * @param dirId      目录名（frontmatter 里没写 name 时兜底当 id 用）
     * @param raw        文件内容
     * @param sourcePath 文件绝对路径（进 error 提示，用户知道去改哪个文件）
     * @param builtin    是否内置技能
     */
    public static SkillDefinition parse(String dirId, String raw, String sourcePath, boolean builtin) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');

        // ---- 1) 切 frontmatter 与正文 ----
        if (!text.startsWith("---")) {
            return broken(dirId, sourcePath, builtin, text,
                "缺少 YAML frontmatter：文件必须以一行 --- 开头");
        }
        int firstEnd = text.indexOf('\n');
        int close = text.indexOf("\n---", firstEnd < 0 ? 0 : firstEnd);
        if (firstEnd < 0 || close < 0) {
            return broken(dirId, sourcePath, builtin, text,
                "frontmatter 没有结束：正文前面需要单独一行 ---");
        }
        String front = text.substring(firstEnd + 1, close + 1);
        int bodyStart = text.indexOf('\n', close + 1);
        String body = bodyStart < 0 ? "" : text.substring(bodyStart + 1).trim();

        // ---- 2) YAML ----
        Map<String, Object> map;
        try {
            Object loaded = new Yaml().load(front);
            if (!(loaded instanceof Map<?, ?> m)) {
                return broken(dirId, sourcePath, builtin, text,
                    "frontmatter 不是键值对（YAML 解析出来是 " + (loaded == null ? "空" : loaded.getClass().getSimpleName()) + "）");
            }
            map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                map.put(String.valueOf(e.getKey()).trim(), e.getValue());
            }
        } catch (Exception e) {
            return broken(dirId, sourcePath, builtin, text,
                "frontmatter YAML 语法错误：" + e.getMessage());
        }

        // ---- 3) 必填字段 ----
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED) {
            if (str(map.get(key)).isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return broken(dirId, sourcePath, builtin, text,
                "frontmatter 缺少必填字段：" + String.join("、", missing));
        }

        String rawId = str(map.get("name"));
        if (!ID_PATTERN.matcher(rawId).matches()) {
            return broken(dirId, sourcePath, builtin, text,
                "name 不合法（" + rawId + "）：只能用字母、数字、. _ -，并且以字母或数字开头");
        }

        SkillDefinition def = new SkillDefinition(
            rawId,
            str(map.get("display_name")),
            str(map.get("description")),
            str(map.get("when_to_use")),
            strList(map.get("keywords")),
            strList(map.get("tools")),
            str(map.get("mode")).isBlank() ? "any" : str(map.get("mode")).toLowerCase(Locale.ROOT),
            str(map.get("model")),
            str(map.get("version")).isBlank() ? "1.0.0" : str(map.get("version")),
            strList(map.get("tags")),
            strList(map.get("task_types")),
            body,
            sourcePath,
            builtin,
            null
        );

        // 正文为空 = 这个技能什么也教不了模型，当坏的报出来（比"加载成功但没用"强）
        if (def.body().isBlank()) {
            return broken(dirId, sourcePath, builtin, text, "正文是空的：技能得写给模型看的指令才有意义");
        }
        return def;
    }

    /** 解析失败的技能：照样返回对象，只是 error 有值、usable()=false。 */
    private static SkillDefinition broken(String dirId, String sourcePath, boolean builtin,
                                          String raw, String error) {
        String id = dirId == null || dirId.isBlank() ? "unknown" : dirId.trim();
        return new SkillDefinition(id, id, "", "", List.of(), List.of(), "any", "", "0.0.0",
            List.of(), List.of(), raw == null ? "" : raw.trim(), sourcePath, builtin, error);
    }

    /**
     * 读 SKILL.md 的字节。
     *
     * <p>【为什么要自己解码】用户技能是用户拿记事本写的，Windows 记事本默认 ANSI(GBK) ——
     * 直接 {@code Files.readString} 用 UTF-8 严格解码会抛 MalformedInputException，
     * 表现就是"我明明写了技能，列表里却是坏的"。这里按 UTF-8 → GBK → 替换字符三级退，
     * 和工具层 read_file 的处理保持一致。</p>
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // 先去掉 UTF-8 BOM：不去掉的话第一行不是 "---"，会白报"缺少 frontmatter"
        int offset = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (Exception notUtf8) {
            try {
                return java.nio.charset.Charset.forName("GBK").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            } catch (Exception e2) {
                return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
            }
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** YAML 列表容错：写成 {@code [a, b]}、写成多行 {@code - a}、或者干脆写一个逗号串都认。 */
    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String s = str(o);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
        for (String part : str(v).split("[,，]")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
