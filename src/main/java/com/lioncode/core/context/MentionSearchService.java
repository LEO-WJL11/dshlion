package com.lioncode.core.context;

import com.lioncode.core.plugin.skill.SkillDefinition;
import com.lioncode.core.plugin.skill.SkillRepository;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.ConversationMessage;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * @ 引用的补全检索：给前端"输入 @ 之后弹出的候选列表"提供数据。
 *
 * <p>三类候选：{@code file}（工作区里的文件）、{@code history}（历史对话）、
 * {@code skill}（已启用的技能）。返回里的 {@code insert} 字段就是前端要插进输入框的文本
 * （形如 {@code @file:src/main/java/X.java}），和服务端展开器认的语法完全一致 ——
 * 两边靠这个字段对齐，前端不需要自己拼。</p>
 *
 * <p>【为什么要"防炸"】文件检索是在用户边打字边调用的（每敲一个字可能就调一次），
 * 而工作区可能是几十万个文件的仓库（node_modules、target 就在里面）。所以：
 * 跳过依赖/构建/IDE 目录、最多遍历 20000 个文件、最深 12 层、整个检索 2 秒硬上限，
 * 超时就把已经找到的部分返回（宁可少给几个候选，也不能把界面卡住）。</p>
 */
@Component
public class MentionSearchService {

    private static final Logger log = LoggerFactory.getLogger(MentionSearchService.class);

    /** 这些目录不该进候选：要么是依赖，要么是构建产物，要么是编辑器垃圾。 */
    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", "target", "dist", "build", "out", "bin", "obj", "vendor",
        ".git", ".idea", ".vscode", ".gradle", ".mvn", ".next", ".nuxt", ".cache",
        "__pycache__", ".venv", "venv", "env", ".tox", "coverage", ".pytest_cache",
        "logs", "tmp", "temp", ".lioncode", "runtime-vulkan", "runtime-cpu", "models"
    );

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 12;
    private static final long DEADLINE_MS = 2000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final SessionManager sessionManager;
    private final ConversationHistory conversationHistory;
    private final SkillRepository skillRepository;
    private final ContextRoots roots;

    public MentionSearchService(SessionManager sessionManager, ConversationHistory conversationHistory,
                               SkillRepository skillRepository, ContextRoots roots) {
        this.sessionManager = sessionManager;
        this.conversationHistory = conversationHistory;
        this.skillRepository = skillRepository;
        this.roots = roots;
    }

    /**
     * 检索候选。
     *
     * @param q         关键词（空 = 全都算匹配，用来做"刚敲 @ 时的默认列表"）
     * @param kind      file / history / skill / all
     * @param root      显式工作区根目录（可空：空则用会话绑定的工作区）
     * @param sessionId 会话 id（可空）
     * @param limit     每个类别的返回条数（默认 20，最多 50）
     */
    public Result search(String q, String kind, String root, String sessionId, Integer limit) {
        long start = System.nanoTime();
        int cap = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String k = kind == null || kind.isBlank() ? "all" : kind.trim().toLowerCase(Locale.ROOT);
        String query = q == null ? "" : q.trim();

        List<Item> items = new ArrayList<>();
        Path rootPath = roots.resolve(sessionId, root);
        String note = null;
        boolean truncated = false;
        int scanned = 0;

        if (k.equals("all") || k.equals("skill")) {
            items.addAll(searchSkills(query, cap));
        }
        if (k.equals("all") || k.equals("history")) {
            items.addAll(searchHistory(query, cap, sessionId));
        }
        if (k.equals("all") || k.equals("file")) {
            // 【为什么显式给的 root 不合法就不往下找了】前端传的 root 是"用户选的那个目录"，
            // 它不存在时如果静默退到会话工作区，用户会看到一堆"别的目录"的文件候选，
            // 却不知道自己在看哪儿 —— 明确告诉他目录不存在，比给错数据强。
            String explicitBad = badRoot(root);
            if (explicitBad != null) {
                note = "根目录不存在或不是目录：" + explicitBad;
            } else if (!Files.isDirectory(rootPath)) {
                note = "工作区目录不存在或不可读：" + rootPath;
            } else {
                Ctx ctx = new Ctx(rootPath, query.toLowerCase(Locale.ROOT), cap, start + DEADLINE_MS * 1_000_000L);
                walk(rootPath, 1, ctx);
                ctx.hits.sort(Comparator.comparingInt((Hit h) -> -h.score)
                    .thenComparingInt(h -> h.rel.length())
                    .thenComparing(h -> h.rel));
                for (Hit h : ctx.hits.subList(0, Math.min(cap, ctx.hits.size()))) {
                    items.add(new Item("file", h.rel, h.name, h.rel + " · " + humanSize(h.size),
                        "@file:" + h.rel));
                }
                truncated = ctx.truncated;
                scanned = ctx.files;
                if (truncated) {
                    note = "已扫描 " + scanned + " 个文件后停止（目录太大或超过 "
                        + (DEADLINE_MS / 1000) + " 秒上限），结果可能不全";
                }
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return new Result(items, truncated, elapsedMs, scanned, rootPath.toString(), note);
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    /** 显式传进来的 root 不合法时返回它（给提示用），合法或没传返回 null。 */
    private static String badRoot(String root) {
        if (root == null || root.isBlank()) {
            return null;
        }
        try {
            return Files.isDirectory(Path.of(root.trim())) ? null : root.trim();
        } catch (Exception e) {
            return root.trim();   // 路径里有非法字符（Windows 上很常见）
        }
    }

    private void walk(Path dir, int depth, Ctx ctx) {
        if (ctx.stop() || depth > MAX_DEPTH) {
            ctx.truncated = true;
            return;
        }
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (ctx.stop()) {
                    return;
                }
                String name = p.getFileName().toString();
                boolean isDir;
                try {
                    isDir = Files.isDirectory(p);
                } catch (Exception e) {
                    continue;
                }
                if (isDir) {
                    // 隐藏目录（.git/.idea/…）和依赖目录一律不进 —— 候选列表里出现
                    // node_modules 里的文件对用户没有任何意义，还白花遍历时间
                    if (name.startsWith(".") || SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    walk(p, depth + 1, ctx);
                } else {
                    ctx.files++;
                    if (ctx.files > MAX_FILES) {
                        ctx.truncated = true;
                        return;
                    }
                    String rel = ctx.root.relativize(p).toString().replace('\\', '/');
                    int score = scoreFile(name, rel, ctx.query);
                    if (score > 0) {
                        long size = 0;
                        try {
                            size = Files.size(p);
                        } catch (Exception ignored) {
                            // 拿不到大小不影响候选，detail 里显示 0
                        }
                        ctx.hits.add(new Hit(score, name, rel, size));
                    }
                }
            }
        } catch (Exception e) {
            // 单个目录读不了（权限/被占用）就跳过它，不要影响其它目录
            log.debug("检索时跳过目录 {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 文件相关度打分。
     *
     * <p>打分而不是简单过滤，是因为候选列表只有 20 行：用户敲 "skill" 时，
     * 文件名正好叫 skill 的必须排在整个路径里恰好含 skill 的前面。</p>
     */
    private static int scoreFile(String name, String rel, String q) {
        if (q.isEmpty()) {
            return 10;
        }
        String n = name.toLowerCase(Locale.ROOT);
        String r = rel.toLowerCase(Locale.ROOT);
        if (n.equals(q)) {
            return 100;
        }
        if (n.startsWith(q)) {
            return 90;
        }
        if (n.contains(q)) {
            return 75;
        }
        String stem = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
        if (stem.equals(q)) {
            return 95;
        }
        if (r.contains(q)) {
            return 55;
        }
        // 打字顺序匹配（skmd → SKILL.md 这类首字母缩写查询）
        if (subsequence(n, q)) {
            return 25;
        }
        return 0;
    }

    private static boolean subsequence(String haystack, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        int i = 0;
        for (int j = 0; j < haystack.length() && i < needle.length(); j++) {
            if (haystack.charAt(j) == needle.charAt(i)) {
                i++;
            }
        }
        return i == needle.length();
    }

    // ------------------------------------------------------------------
    // 历史对话
    // ------------------------------------------------------------------

    private List<Item> searchHistory(String q, int cap, String currentSessionId) {
        String query = q.toLowerCase(Locale.ROOT);
        record Scored(int score, SessionManager.Session session, String first) {}
        List<Scored> hits = new ArrayList<>();
        for (SessionManager.Session s : sessionManager.getAllSessions()) {
            String id = s.sessionId() == null ? "" : s.sessionId();
            if (id.equals(currentSessionId)) {
                continue;   // 当前会话不用 @ 引用自己
            }
            String title = s.displayName() == null ? "" : s.displayName();
            String first = firstUserMessage(id);
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            String lowerFirst = first.toLowerCase(Locale.ROOT);
            int score;
            if (query.isEmpty()) {
                score = 50;
            } else if (id.toLowerCase(Locale.ROOT).equals(query)) {
                score = 100;
            } else if (lowerTitle.contains(query)) {
                score = 85;
            } else if (id.toLowerCase(Locale.ROOT).startsWith(query)) {
                score = 70;
            } else if (lowerFirst.contains(query)) {
                score = 60;
            } else {
                score = 0;
            }
            if (score > 0) {
                hits.add(new Scored(score, s, first));
            }
        }
        hits.sort(Comparator.comparingInt((Scored h) -> -h.score())
            .thenComparing(h -> h.session().createdAt() == null ? java.time.Instant.EPOCH
                : h.session().createdAt(), Comparator.reverseOrder()));
        List<Item> out = new ArrayList<>();
        for (Scored h : hits.subList(0, Math.min(cap, hits.size()))) {
            String id = h.session().sessionId();
            int count = conversationHistory.getMessageCount(id);
            String detail = count + " 条消息"
                + (h.first().isBlank() ? "" : " · " + SkillDefinition.oneLine(h.first(), 60));
            out.add(new Item("history", id, h.session().displayName(), detail, "@history:" + id));
        }
        return out;
    }

    private String firstUserMessage(String sessionId) {
        for (ConversationMessage m : conversationHistory.getHistory(sessionId)) {
            if ("user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 技能
    // ------------------------------------------------------------------

    private List<Item> searchSkills(String q, int cap) {
        String query = q.toLowerCase(Locale.ROOT);
        record Scored(int score, SkillDefinition def) {}
        List<Scored> hits = new ArrayList<>();
        for (SkillDefinition d : skillRepository.list()) {
            if (!d.usable() || !skillRepository.isEnabled(d.id())) {
                continue;
            }
            int score;
            if (query.isEmpty()) {
                score = 50;
            } else if (d.id().toLowerCase(Locale.ROOT).equals(query)) {
                score = 100;
            } else if (d.keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).equals(query))) {
                score = 90;
            } else if (d.id().toLowerCase(Locale.ROOT).contains(query)) {
                score = 80;
            } else if (d.displayName().toLowerCase(Locale.ROOT).contains(query)) {
                score = 70;
            } else if (d.keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(query))) {
                score = 65;
            } else if (d.description().toLowerCase(Locale.ROOT).contains(query)) {
                score = 40;
            } else {
                score = 0;
            }
            if (score > 0) {
                hits.add(new Scored(score, d));
            }
        }
        hits.sort(Comparator.comparingInt((Scored h) -> -h.score()).thenComparing(h -> h.def().id()));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scored h : hits) {
            if (!seen.add(h.def().id())) {
                continue;
            }
            out.add(new Item("skill", h.def().id(), h.def().displayName(),
                h.def().description(), "@skill:" + h.def().id()));
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /** 遍历状态（文件数、命中、截止时间）。 */
    private static final class Ctx {
        final Path root;
        final String query;
        final int limit;
        final long deadlineNanos;
        final List<Hit> hits = new ArrayList<>();
        int files = 0;
        boolean truncated = false;

        Ctx(Path root, String query, int limit, long deadlineNanos) {
            this.root = root;
            this.query = query;
            this.limit = limit;
            this.deadlineNanos = deadlineNanos;
        }

        boolean stop() {
            return files > MAX_FILES || System.nanoTime() > deadlineNanos;
        }
    }

    private record Hit(int score, String name, String rel, long size) {
    }

    /**
     * 一条候选。
     *
     * @param type   文件是 {@code file}、历史对话是 {@code history}、技能是 {@code skill}
     * @param id     文件=相对路径，历史=会话 id，技能=技能 id
     * @param label  界面上显示的主文本
     * @param detail 次要说明（路径/消息数/技能说明）
     * @param insert 直接插进输入框的文本，服务端展开器认这个格式
     */
    public record Item(String type, String id, String label, String detail, String insert) {
    }

    /**
     * 检索结果。
     *
     * @param items      候选（已按相关度排序）
     * @param truncated  文件检索是否因为数量/超时被截断
     * @param elapsedMs  耗时（毫秒，用来盯"2 秒上限"是否真的生效）
     * @param scannedFiles 实际遍历了多少个文件
     * @param root       实际使用的根目录
     * @param note       需要提示给用户的话（目录不存在、结果被截断等），没有就是 null
     */
    public record Result(List<Item> items, boolean truncated, long elapsedMs, int scannedFiles,
                         String root, String note) {
    }
}
