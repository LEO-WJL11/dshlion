package com.lioncode.core.plugin.skill;

import com.lioncode.model.config.AppConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能仓库：扫描技能目录、缓存解析结果、管理启用开关与会话指定技能。
 *
 * <p>两个来源，用户优先级更高：
 * <ol>
 *   <li><b>内置</b>：仓库 {@code skills/}（随安装包发布）—— {@code skills/<id>/SKILL.md}</li>
 *   <li><b>用户</b>：{@code <配置目录>/skills/}，也就是 {@code ~/.lioncode/skills/}，
 *       和 {@code app-config.json} 同一个目录（跟着现有 AppConfigStore 走，不另造一套）</li>
 * </ol>
 * 同名（同 id）时用户的那份覆盖内置的那份 —— 用户想改内置技能的行为不用动安装目录。</p>
 *
 * <p>【为什么缓存 + 手动 reload】技能是在系统提示词里出现的，每条消息都要用；
 * 每轮去读盘既慢又可能读到写了一半的文件。所以启动时扫一次、缓存住，
 * 用户改完文件调 {@code POST /api/skills/reload} 刷一次。</p>
 */
@Component
public class SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

    /** 技能文件名（通用格式约定，写死在这里，改格式要同步改文档）。 */
    public static final String SKILL_FILE = "SKILL.md";

    /** 关闭开关存在 app-config.json 的这个键下（和 activeAdapter 那些配置同一个文件）。 */
    private static final String CFG_DISABLED = "skills.disabled";

    /** 技能目录在提示词里的最大行数：目录只用来"让模型知道有哪些技能"，塞不下就截断。 */
    private static final int CATALOG_MAX = 20;

    private final AppConfigStore configStore;

    /** 显式指定内置技能目录（安装包/测试用；留空就按候选目录自动找）。 */
    @Value("${lion.skills.dir:}")
    private String configuredBuiltinDir;

    /** 解析后的技能：id -> 定义（LinkedHashMap 保证顺序稳定，提示词才好复用缓存）。 */
    private volatile Map<String, SkillDefinition> skills = Map.of();

    /** 内置/用户目录（第一次扫描后定下来，列表接口要返回给前端显示）。 */
    private volatile Path builtinDir;
    private volatile Path userSkillsDir;

    /** 会话级"指定技能"（POST /api/skills/active 钉住的），内存存：会话没了这条也就没意义了。 */
    private final Map<String, Set<String>> activeBySession = new ConcurrentHashMap<>();

    public SkillRepository(AppConfigStore configStore) {
        this.configStore = configStore;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    // ------------------------------------------------------------------
    // 扫描 / 重载
    // ------------------------------------------------------------------

    /**
     * 重新扫描技能目录。
     *
     * <p>先内置后用户：同 id 时用户那份后写进去，自然覆盖。</p>
     *
     * @return 扫描结果（数量 + 坏掉的技能数），接口和日志都用它
     */
    public synchronized ReloadResult reload() {
        Path builtin = resolveBuiltinDir();
        Path user = Path.of(System.getProperty("user.home", "."), ".lioncode", "skills");
        this.builtinDir = builtin;
        this.userSkillsDir = user;

        // 用户技能目录顺手建出来：不然用户看到接口里返回的路径，却发现自己机器上没这个目录，
        // 还得自己猜层级（技能目录是变了以后才扫，所以建目录不影响本次结果）
        try {
            Files.createDirectories(user);
        } catch (Exception e) {
            log.debug("创建用户技能目录失败（不影响使用）: {} - {}", user, e.getMessage());
        }

        Map<String, SkillDefinition> found = new LinkedHashMap<>();
        int broken = 0;
        broken += scanDir(builtin, true, found);
        broken += scanDir(user, false, found);

        // 坏掉的技能也留在列表里（用户要能看到错在哪），但不参与匹配
        int errorCount = 0;
        for (SkillDefinition d : found.values()) {
            if (!d.usable()) {
                errorCount++;
            }
        }
        // 必须是不可变 + **保序**的：Map.copyOf 的顺序是未定义的，而目录顺序一变，
        // 系统提示词就跟着变（缓存失效、diff 难看），所以这里用 unmodifiableMap 包住 LinkedHashMap
        this.skills = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(found));
        log.info("技能扫描完成：{} 个（内置目录 {}，用户目录 {}），其中 {} 个有问题",
            found.size(), builtin, user, errorCount);
        if (found.isEmpty()) {
            log.warn("一个技能都没扫到。内置技能应该在 {}；如果你是从别的目录启动的，"
                + "可以用 -Dlion.skills.dir=... 指定技能目录", builtin);
        }
        return new ReloadResult(found.size(), errorCount, broken);
    }

    /** 扫一个目录：只认 {@code <id>/SKILL.md} 这种两层结构。 */
    private int scanDir(Path dir, boolean builtin, Map<String, SkillDefinition> into) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int broken = 0;
        try (var entries = Files.newDirectoryStream(dir)) {
            List<Path> subDirs = new ArrayList<>();
            for (Path p : entries) {
                if (Files.isDirectory(p) && Files.isRegularFile(p.resolve(SKILL_FILE))) {
                    subDirs.add(p);
                }
            }
            // 排序后处理：文件系统的顺序不稳定，排序让每次扫描结果一致（列表/提示词才稳定）
            subDirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path sub : subDirs) {
                Path file = sub.resolve(SKILL_FILE);
                String dirId = sub.getFileName().toString();
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    SkillDefinition def = SkillParser.parse(dirId, SkillParser.decode(bytes),
                        file.toAbsolutePath().toString(), builtin);
                    if (!def.usable()) {
                        broken++;
                        log.warn("技能 {} 有问题（{}）：{}", dirId, file, def.error());
                    }
                    into.put(def.id(), def);
                } catch (Exception e) {
                    // 读盘失败（权限/文件被占用）同样算"坏技能"，不能把整个扫描带崩
                    broken++;
                    SkillDefinition def = SkillParser.parse(dirId, "", file.toAbsolutePath().toString(),
                        builtin);
                    into.put(def.id(), def);
                    log.warn("读取技能文件失败: {} - {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描技能目录失败: {} - {}", dir, e.getMessage());
        }
        return broken;
    }

    /**
     * 找内置技能目录。
     *
     * <p>【为什么给一堆候选】和 {@code LocalModelRuntime.appDirs()} 是同一个理由：
     * 这个程序有好几种启动方式（dist\启动LionBox.bat、项目根目录启动、安装目录启动、
     * 开发时 target\ 里跑 jar），工作目录各不相同，"skills 在隔壁"这件事必须多找几个地方：
     * <ol>
     *   <li>显式配置 {@code -Dlion.skills.dir=...}</li>
     *   <li>{@code lionbox.home}（启动脚本会设）</li>
     *   <li>{@code ApplicationHome}（Spring Boot 自己算出来的程序所在目录，能认出 fat jar）</li>
     *   <li>进程工作目录、工作目录/dist</li>
     * </ol>
     * 返回第一个"真的存在"的；一个都不存在时返回第一个候选，日志里已经警告过了。</p>
     *
     * <p>【踩过的坑】第一版只用 {@code getProtectionDomain().getCodeSource().getLocation()}：
     * 打成 Spring Boot fat jar 以后，这个 URL 是 {@code jar:nested:/.../app.jar/!BOOT-INF/classes/!/}
     * 这种形态，{@code Path.of(uri)} 直接抛异常被 catch 吃掉 —— 结果"技能目录"退化成
     * 进程工作目录下的 skills，四个内置技能一个都扫不到（测试里就是这么炸的）。
     * {@link org.springframework.boot.system.ApplicationHome} 就是为这件事写的，先问它。</p>
     */
    private Path resolveBuiltinDir() {
        List<Path> candidates = new ArrayList<>();
        if (configuredBuiltinDir != null && !configuredBuiltinDir.isBlank()) {
            candidates.add(Path.of(configuredBuiltinDir.trim()));
        }
        String home = System.getProperty("lionbox.home");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "skills"));
        }
        // Spring Boot 的"程序装在哪"（fat jar 也能认）；注意它返回的是 java.io.File
        try {
            org.springframework.boot.system.ApplicationHome appHome =
                new org.springframework.boot.system.ApplicationHome(SkillRepository.class);
            java.io.File dir = appHome.getDir();
            if (dir != null) {
                addDirCandidates(candidates, dir.toPath());
            }
        } catch (Exception | LinkageError ignored) {
            // 拿不到就继续用后面的候选
        }
        // 普通 class 目录 / 可执行 jar（非 fat jar）时的兜底
        try {
            java.net.URL loc = SkillRepository.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null && "file".equalsIgnoreCase(loc.getProtocol())) {
                Path p = Path.of(loc.toURI());
                Path dir = Files.isDirectory(p) ? p : p.getParent();
                if (dir != null) {
                    addDirCandidates(candidates, dir);
                }
            }
        } catch (Exception ignored) {
            // 拿不到就只用前面的候选
        }
        String cwd = System.getProperty("user.dir");
        if (cwd != null && !cwd.isBlank()) {
            candidates.add(Path.of(cwd, "skills"));
            candidates.add(Path.of(cwd, "dist", "skills"));
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        Path fallback = candidates.isEmpty() ? Path.of("skills") : candidates.get(0);
        return fallback.toAbsolutePath().normalize();
    }

    /** 从一个基准目录推出 skills/ 的候选：自己、dist 下、上一级（开发时 jar 在 target/ 里）。 */
    private static void addDirCandidates(List<Path> candidates, Path dir) {
        candidates.add(dir.resolve("skills"));
        candidates.add(dir.resolve("dist").resolve("skills"));
        if (dir.getParent() != null) {
            candidates.add(dir.getParent().resolve("skills"));
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 全部技能（含坏掉的、含被禁用的），顺序稳定。 */
    public List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }

    /** 只有可用的（解析没错）+ 用户没关掉的技能 —— 提示词和 skill_load 只认这些。 */
    public List<SkillDefinition> enabled() {
        Set<String> disabled = disabledIds();
        return skills.values().stream()
            .filter(SkillDefinition::usable)
            .filter(d -> !disabled.contains(d.id()))
            .toList();
    }

    /**
     * 按 id（或 {@code skill.<id>} 这种老插件 id 写法）找技能。
     *
     * <p>两种写法都认是有原因的：老的插件 id 是 {@code skill.backend}，
     * 用户在界面上、日志里、甚至从 {@code /api/plugins} 复制出来的都是带前缀的，
     * 只认短 id 会让他"明明有这个技能却说没有"。</p>
     */
    public Optional<SkillDefinition> find(String idOrToken) {
        if (idOrToken == null || idOrToken.isBlank()) {
            return Optional.empty();
        }
        String key = idOrToken.trim();
        SkillDefinition direct = skills.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("skill.")) {
            SkillDefinition stripped = skills.get(key.substring("skill.".length()));
            if (stripped != null) {
                return Optional.of(stripped);
            }
            lower = lower.substring("skill.".length());
        }
        for (SkillDefinition d : skills.values()) {
            if (d.id().toLowerCase(Locale.ROOT).equals(lower)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** 找"能用的"技能（不存在 / 坏了 / 被禁用 都返回 empty，调用方据此给出原因）。 */
    public Optional<SkillDefinition> findEnabled(String idOrToken) {
        return find(idOrToken).filter(d -> d.usable() && isEnabled(d.id()));
    }

    /** 这个技能为什么不能用（给模型/用户一句能照着改的话）；能用时返回 null。 */
    public String unusableReason(String idOrToken) {
        Optional<SkillDefinition> opt = find(idOrToken);
        if (opt.isEmpty()) {
            List<String> ids = enabled().stream().map(SkillDefinition::id).toList();
            return "没有这个技能: " + idOrToken + "（可用技能: "
                + (ids.isEmpty() ? "一个都没有" : String.join("、", ids)) + "）";
        }
        SkillDefinition d = opt.get();
        if (!d.usable()) {
            return "技能 " + d.id() + " 有问题，用不了：" + d.error();
        }
        if (!isEnabled(d.id())) {
            return "技能 " + d.id() + " 已被禁用（POST /api/skills/" + d.id() + "/enable 可以打开）";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 启用 / 禁用（持久化到 app-config.json）
    // ------------------------------------------------------------------

    public boolean isEnabled(String id) {
        return !disabledIds().contains(id);
    }

    /** 打开/关闭一个技能；返回 false 表示没有这个技能。 */
    public synchronized boolean setEnabled(String id, boolean enabled) {
        Optional<SkillDefinition> def = find(id);
        if (def.isEmpty()) {
            return false;
        }
        String realId = def.get().id();
        Set<String> disabled = new LinkedHashSet<>(disabledIds());
        if (enabled) {
            disabled.remove(realId);
        } else {
            disabled.add(realId);
        }
        configStore.set(CFG_DISABLED, new ArrayList<>(disabled));
        log.info("技能 {} 已{}", realId, enabled ? "启用" : "禁用");
        return true;
    }

    /** 已经被关掉的技能 id 集合（配置里可能残留已删除的技能，读的时候顺手过滤）。 */
    public Set<String> disabledIds() {
        Object raw = configStore.get(CFG_DISABLED, List.of());
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 会话指定技能
    // ------------------------------------------------------------------

    /** 这个会话被钉住的技能 id（过滤掉已经不存在的）。 */
    public List<String> activeSkills(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        Set<String> ids = activeBySession.getOrDefault(sessionId, Set.of());
        return ids.stream().filter(id -> skills.containsKey(id)).toList();
    }

    /**
     * 给会话钉住技能（空列表=取消钉住）。
     *
     * @return 不认识的 id 列表（调用方据此报错，其余照常钉上）
     */
    public synchronized List<String> setActive(String sessionId, List<String> ids) {
        List<String> unknown = new ArrayList<>();
        Set<String> resolved = new LinkedHashSet<>();
        if (ids != null) {
            for (String raw : ids) {
                Optional<SkillDefinition> def = findEnabled(raw);
                if (def.isPresent()) {
                    resolved.add(def.get().id());
                } else if (raw != null && !raw.isBlank()) {
                    unknown.add(raw.trim());
                }
            }
        }
        if (sessionId != null) {
            if (resolved.isEmpty()) {
                activeBySession.remove(sessionId);
            } else {
                activeBySession.put(sessionId, resolved);
            }
        }
        return unknown;
    }

    /** 钉住的技能定义（可用的），给提示词注入用。 */
    public List<SkillDefinition> activeDefinitions(String sessionId) {
        List<SkillDefinition> out = new ArrayList<>();
        for (String id : activeSkills(sessionId)) {
            findEnabled(id).ifPresent(out::add);
        }
        return out;
    }

    /** 会话被销毁时清一下（避免长时间运行攒一堆死会话的钉住关系）。 */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            activeBySession.remove(sessionId);
        }
    }

    // ------------------------------------------------------------------
    // 提示词
    // ------------------------------------------------------------------

    /**
     * 技能目录（系统提示词里那段"有哪些技能可用"）。
     *
     * <p>【为什么只给一行、不给正文】提示词每轮都要发一遍，本地模型 11 token/s，
     * 把技能正文全塞进去等于每条消息先花一分钟"读说明书"。所以目录只列
     * id + 名字 + 一句话 + 什么时候用，模型决定要用哪个时再调 {@code skill_load} 取正文。</p>
     */
    public String catalogText() {
        List<SkillDefinition> list = enabled();
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 技能（按需加载）\n");
        sb.append("下面每个技能是一套现成的做法。**某个技能匹配当前任务时，");
        sb.append("先调 skill_load（参数 name = 技能 id）拿到完整指令，再动手**；不符合就别加载。\n");
        int shown = 0;
        for (SkillDefinition d : list) {
            if (shown >= CATALOG_MAX) {
                break;
            }
            sb.append(d.catalogLine()).append("\n");
            shown++;
        }
        if (list.size() > shown) {
            sb.append("（还有 ").append(list.size() - shown).append(" 个技能没列出来，可以调 skill_load 试名字）\n");
        }
        return sb.toString();
    }

    /** 目录接口要返回的路径字符串（不存在也给路径，用户好照着建）。 */
    public String builtinDirString() {
        return builtinDir == null ? "" : builtinDir.toString();
    }

    public String userDirString() {
        return userSkillsDir == null ? "" : userSkillsDir.toString();
    }

    /**
     * 扫描结果。
     *
     * @param count  扫到几个技能（含坏掉的）
     * @param errors 其中几个是坏的
     * @param broken 本次扫描里读取/解析失败的次数（含目录读不出来的）
     */
    public record ReloadResult(int count, int errors, int broken) {}
}
