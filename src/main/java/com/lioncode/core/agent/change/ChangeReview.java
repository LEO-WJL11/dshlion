package com.lioncode.core.agent.change;

import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.PluginSettings;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.ConversationMessage;
import com.lioncode.core.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 改动的人工审核闸门。
 *
 * <p>【用户要的流程】"AI 写代码 → 人工审核 → 通过了这个文件才真正被改；否则打回去重写，相当于作业。"
 * 所以改文件的工具不再是"一调就落盘"，而是先把**改动**登记成一条待审记录，立刻返回
 * "已提交待审、未落盘"。人在界面（WebUI 或 VS Code 侧栏）看到 diff，点通过才真写，
 * 点打回就丢掉并把理由回给模型，让它重写。</p>
 *
 * <p>【为什么放在工具里而不是 AgentLoop 里】只有工具自己知道"改完的文件内容长什么样"
 * （尤其是 modify_file 的按行替换），在循环层拦是拿不到 diff 的。所以闸门做成一个
 * 由工具调用的服务：{@link #intercept} —— 一行调用，命中就返回"待审"结果。</p>
 *
 * <p>【开关】认插件 {@code plugin.change-review} 的开关（设置里可关）。关掉就是老行为：直接落盘。
 * 默认开 —— 这是用户明确要的默认。</p>
 */
@Component
public class ChangeReview {

    private static final Logger log = LoggerFactory.getLogger(ChangeReview.class);

    /** 插件 id：设置里那个开关就是它 */
    public static final String PLUGIN_ID = "plugin.change-review";

    private final ConversationHistory history;
    private final PluginRegistry pluginRegistry;
    private final PluginSettings settings;

    /** 出厂默认：开（用户要的就是"先审后用"） */
    private final boolean enabledByDefault;

    /** id -> 改动。审完也留着（界面能回看"通过/打回了什么"），重启后清空 */
    private final Map<String, PendingChange> changes = new ConcurrentHashMap<>();

    /** 每个会话最多留多少条记录，防止长跑会话把内存堆满 */
    private static final int MAX_PER_SESSION = 200;

    private final AtomicInteger seq = new AtomicInteger();

    public ChangeReview(ConversationHistory history, PluginRegistry pluginRegistry,
                        PluginSettings settings,
                        @Value("${lionbox.change-review.enabled:true}") boolean enabledByDefault) {
        this.history = history;
        this.pluginRegistry = pluginRegistry;
        this.settings = settings;
        this.enabledByDefault = enabledByDefault;
        log.info("改动人工审核: {}", enabledByDefault ? "默认开启（改文件先待审）" : "默认关闭（直接落盘）");
    }

    /**
     * 审核开着吗。
     *
     * <p>判定顺序：用户在设置里点过的开关（插件开关，落盘、重启还在）→ 配置文件默认值。
     * 插件没注册（比如被整个禁用了）也按默认值走。</p>
     */
    public boolean enabled() {
        Plugin p = pluginRegistry.getById(PLUGIN_ID).orElse(null);
        if (p == null) {
            // 插件没在注册表里：仍然尊重用户在设置里点过的开关
            return settings.isEnabled(PLUGIN_ID, enabledByDefault);
        }
        return settings.isEnabled(p);
    }

    /**
     * 闸门：改文件前调它。
     *
     * @return 有值 = **不要落盘**，直接把返回的 ToolResult 交回去（已经登记待审）；空 = 照常落盘
     */
    public Optional<ToolResult> intercept(String toolName, String path, String oldContent, String newContent) {
        if (!enabled()) {
            return Optional.empty();
        }
        String sessionId = SessionContext.get();
        PendingChange c = submit(sessionId, toolName, path, oldContent, newContent);
        String action = newContent == null ? "删除" : (oldContent == null ? "新建" : "修改");
        return Optional.of(ToolResult.success(
            "已提交人工审核，**还没落盘**。\n"
            + "  编号：" + c.id() + "\n"
            + "  文件：" + path + "（" + action + "）\n"
            + "  人在界面上点「通过」之后才会真正写进去；点「打回」你会收到理由，按理由重写。\n"
            + "  现在继续做别的、或者直接结束这一轮都行 —— 别再重复提交同一个文件的改动。"));
    }

    /** 登记一条待审改动 */
    public PendingChange submit(String sessionId, String toolName, String path,
                                String oldContent, String newContent) {
        String id = "C" + System.currentTimeMillis() % 100000 + "-" + seq.incrementAndGet();
        String diff = Diff.render(path, oldContent, newContent);
        PendingChange c = new PendingChange(id, sessionId, toolName, path,
            oldContent != null, oldContent, newContent, diff,
            System.currentTimeMillis(), PendingChange.PENDING, null);
        changes.put(id, c);
        trim(sessionId);
        log.info("待审改动 {}：{} {}（工具 {}，会话 {}）", id,
            oldContent == null ? "新建" : (newContent == null ? "删除" : "修改"), path, toolName, sessionId);
        return c;
    }

    /** 待审列表（默认只给待审；includeDecided=true 连已通过/已打回的一起给，界面回看用） */
    public List<PendingChange> list(String sessionId, boolean includeDecided) {
        List<PendingChange> out = new ArrayList<>();
        for (PendingChange c : changes.values()) {
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(c.sessionId())) {
                continue;
            }
            if (!includeDecided && !PendingChange.PENDING.equals(c.status())) {
                continue;
            }
            out.add(c);
        }
        out.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return out;
    }

    public PendingChange get(String id) {
        return changes.get(id);
    }

    /** 通过：真落盘，并把结论告诉模型（下一条消息它就看得见） */
    public PendingChange approve(String id) {
        PendingChange c = require(id);
        if (!PendingChange.PENDING.equals(c.status())) {
            throw new IllegalStateException("这条改动已经处理过了：" + c.status());
        }
        try {
            Path p = Path.of(c.path());
            if (c.newContent() == null) {
                Files.deleteIfExists(p);
            } else {
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, c.newContent(), charsetOf(p));
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入失败: " + e.getMessage(), e);
        }
        PendingChange done = c.withStatus(PendingChange.APPROVED, null);
        changes.put(id, done);
        note(c.sessionId(), "【人工审核】改动 " + id + " 已通过，已写入 " + c.path()
            + "（" + (c.newContent() == null ? "删除" : "写入 " + c.newContent().length() + " 字符") + "）。");
        log.info("改动 {} 已通过并落盘: {}", id, c.path());
        return done;
    }

    /** 打回：不落盘，把理由回给模型让它重写 */
    public PendingChange reject(String id, String reason) {
        PendingChange c = require(id);
        if (!PendingChange.PENDING.equals(c.status())) {
            throw new IllegalStateException("这条改动已经处理过了：" + c.status());
        }
        PendingChange done = c.withStatus(PendingChange.REJECTED, reason == null ? "" : reason);
        changes.put(id, done);
        note(c.sessionId(), "【人工审核】改动 " + id + "（" + c.path() + "）被**打回**，没有落盘。"
            + (reason == null || reason.isBlank() ? "" : "打回理由：" + reason + "。")
            + "请按这个理由重写，然后重新提交。");
        log.info("改动 {} 被打回: {}（理由：{}）", id, c.path(), reason);
        return done;
    }

    /**
     * 把结论写进会话历史。
     *
     * <p>写成 system 消息：AgentLoop 重建历史时会把它降级成带【系统提示】前缀的 user 消息，
     * 模型下一轮必然看到；用户也能在对话里看到"这条改动通过了/被打回了"。</p>
     */
    private void note(String sessionId, String text) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        history.addMessage(ConversationMessage.system(sessionId, text));
    }

    private PendingChange require(String id) {
        PendingChange c = changes.get(id);
        if (c == null) {
            throw new IllegalArgumentException("没有这条改动：" + id);
        }
        return c;
    }

    /** 每个会话只留最近 MAX_PER_SESSION 条，超了丢最老的（已处理过的优先丢） */
    private void trim(String sessionId) {
        if (sessionId == null) {
            return;
        }
        List<PendingChange> mine = list(sessionId, true);
        if (mine.size() <= MAX_PER_SESSION) {
            return;
        }
        int drop = mine.size() - MAX_PER_SESSION;
        for (PendingChange c : mine) {
            if (drop <= 0) {
                break;
            }
            if (!PendingChange.PENDING.equals(c.status())) {
                changes.remove(c.id());
                drop--;
            }
        }
    }

    /** 统计信息（界面顶部显示"还有几条待审"） */
    public Map<String, Object> stats(String sessionId) {
        List<PendingChange> pending = list(sessionId, false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled());
        m.put("pendingCount", pending.size());
        m.put("pending", pending.stream().map(PendingChange::summary).toList());
        return m;
    }

    private static Charset charsetOf(Path p) {
        // 和工具那边的口径一致：已有文件按原编码写回；新文件 UTF-8
        try {
            if (Files.isRegularFile(p)) {
                byte[] head = Files.readAllBytes(p);
                String utf8 = new String(head, java.nio.charset.StandardCharsets.UTF_8);
                if (utf8.indexOf('\uFFFD') < 0) {
                    return java.nio.charset.StandardCharsets.UTF_8;
                }
                return Charset.forName("GBK");
            }
        } catch (Exception ignored) {
            // 读不了就按 UTF-8
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }
}
