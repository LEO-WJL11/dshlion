package com.lioncode.core.plugin.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一个技能的完整定义 —— 也就是一份 {@code SKILL.md} 解析出来的结果。
 *
 * <p>【为什么要做成"文件 + 记录"而不是继续用 Java 类】技能原本是四个写死的 Java 类
 * （BackendSkill 之类），加一个技能就得改代码、重新编译、重新打包；用户想自己加技能
 * 更是完全没门。改成通用格式（YAML frontmatter + Markdown 正文）以后：
 * 内置技能放仓库 {@code skills/}，用户技能放 {@code ~/.lioncode/skills/}，
 * 加技能 = 新建一个目录，不用碰 Java。</p>
 *
 * <p>【为什么 error 是一个字段而不是抛异常】一份写坏的 SKILL.md（frontmatter 缺字段、
 * YAML 语法错）绝不能让应用起不来 —— 用户只是笔误，不该整个软件打不开。所以解析失败
 * 也返回一个 SkillDefinition，只是 {@link #error} 有值、{@link #usable()} 为 false，
 * 列表接口里照样看得到它、也知道错在哪。</p>
 *
 * @param id          技能唯一 id（frontmatter 的 name；也是目录名、{@code @skill:<id>} 里那个 id）
 * @param displayName 给人看的名字（frontmatter 的 display_name）
 * @param description 一句话说明能力（frontmatter 的 description —— 模型就是靠这句决定要不要用）
 * @param whenToUse   什么时候该用（frontmatter 的 when_to_use）
 * @param keywords    关键词兜底匹配用
 * @param tools       本技能要用到的工具名（如 read_file）
 * @param mode        建议工作模式：minimal / standard / any（空=any）
 * @param model       指定跑这个技能的模型（空=用当前模型）
 * @param version     版本号
 * @param tags        分类标签
 * @param taskTypes   任务类型描述（老 SkillPlugin#getTaskTypeDescriptions 的等价物）
 * @param body        正文（给模型看的具体指令）
 * @param sourcePath  SKILL.md 的绝对路径（排错用：用户能直接去改这个文件）
 * @param builtin     是否内置技能（内置来自仓库 skills/，用户来自配置目录）
 * @param error       解析错误；null/空 表示这份技能可用
 */
public record SkillDefinition(
    String id,
    String displayName,
    String description,
    String whenToUse,
    List<String> keywords,
    List<String> tools,
    String mode,
    String model,
    String version,
    List<String> tags,
    List<String> taskTypes,
    String body,
    String sourcePath,
    boolean builtin,
    String error
) {

    /**
     * 内置技能的 id 集合。
     *
     * <p>【这个常量是干什么的】AgentLoop 里那份老的 {@code buildSkillPrompt()} 会按关键词
     * 把"适用的技能"整段塞进系统提示词，它读的就是这四个 Java 兼容壳。所以注入技能目录时
     * 要避开这四个 —— 否则同一个技能的正文会出现两遍，白烧 token（本地模型 11 token/s，
     * 多一千 token 就是多一分半钟）。</p>
     *
     * <p>用户自己加的技能没有 Java 壳，老路径覆盖不到，由 {@link SkillCatalogSpi} 按关键词补上。</p>
     */
    public static final List<String> LEGACY_IDS = List.of("backend", "client", "document", "frontend");

    /** 工具名 → 老插件 id 的映射：让 {@link #requiredToolIds()} 还能返回老格式的 id。 */
    private static final Map<String, String> TOOL_PLUGIN_IDS = Map.ofEntries(
        Map.entry("read_file", "tool.file.read"),
        Map.entry("write_file", "tool.file.write"),
        Map.entry("create_file", "tool.file.write"),
        Map.entry("append_file", "tool.file.write"),
        Map.entry("modify_file", "tool.file.modify"),
        Map.entry("search_in_files", "tool.file.search"),
        Map.entry("glob_files", "tool.file.search"),
        Map.entry("list_directory", "tool.file.search"),
        Map.entry("execute_command", "tool.shell.execute"),
        Map.entry("run_background", "tool.shell.execute"),
        Map.entry("git_status", "tool.git.status"),
        Map.entry("git_commit", "tool.git.commit")
    );

    /** 这份技能能不能用（解析没出错、正文非空）。 */
    public boolean usable() {
        return (error == null || error.isBlank()) && body != null && !body.isBlank();
    }

    /** 老接口 {@code SkillPlugin#getRequiredToolIds()} 要的插件 id 形式。 */
    public List<String> requiredToolIds() {
        List<String> out = new ArrayList<>();
        for (String t : tools) {
            if (t == null || t.isBlank()) {
                continue;
            }
            out.add(TOOL_PLUGIN_IDS.getOrDefault(t.trim().toLowerCase(Locale.ROOT), t.trim()));
        }
        return out;
    }

    /**
     * 关键词是否命中这条用户消息（老 {@code isApplicable()} 的通用版）。
     *
     * <p>匹配规则分两种，别一刀切：
     * <ul>
     *   <li>纯 ASCII 关键词（api、java、css）按**词边界**匹配：老实现是
     *       {@code contains}，于是 {@code api} 会在 "rapid" 里命中、{@code java} 会在
     *       "javascript" 里命中 —— 前者把后端技能骗出来，后者让"改 JS"也触发后端技能。</li>
     *   <li>含中文的关键词照旧用 contains：中文没有词边界这回事。</li>
     * </ul>
     */
    public boolean matchesKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = userMessage.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            String k = kw.trim().toLowerCase(Locale.ROOT);
            if (isAscii(k)) {
                // 前面不能是字母/数字/下划线；后面不能紧跟字母（允许跟数字：qt5 也算 qt）
                if (Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(k) + "(?![a-z])")
                        .matcher(lower).find()) {
                    return true;
                }
            } else if (lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 给模型看的一行目录：{@code id — 名字：说明（什么时候用）}。 */
    public String catalogLine() {
        StringBuilder sb = new StringBuilder("- ").append(id).append(" — ").append(displayName)
            .append("：").append(oneLine(description, 120));
        if (whenToUse != null && !whenToUse.isBlank()) {
            sb.append("（什么时候用：").append(oneLine(whenToUse, 140)).append("）");
        }
        return sb.toString();
    }

    /** 界面/接口里用的正文预览（压成一行、截断）。 */
    public String bodyPreview(int max) {
        return oneLine(body, max);
    }

    /** 一份技能是不是被这份定义"负责"（判断某个 id 是不是内置四件套）。 */
    public static boolean isLegacy(String id) {
        return id != null && LEGACY_IDS.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** 合并空白并截断，给"一行显示"的场合用。 */
    public static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
