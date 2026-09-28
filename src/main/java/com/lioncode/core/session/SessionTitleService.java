package com.lioncode.core.session;

import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ChatMessage;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.adapter.ModelResponse;
import com.lioncode.model.config.AppConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话标题生成
 *
 * 之前界面上的会话名是会话ID的前 8 位（看起来就是一串随机数），
 * 根本分不清哪条是哪条。现在改成：用户在会话里发出**第一条消息**后，
 * 拿这条消息让模型生成一个短标题，用户之后可以随时手动改。
 *
 * 几个刻意的设计：
 *  1. **异步**：生成标题是一次额外的模型调用，绝不能让用户的首条消息多等几秒。
 *     所以丢到后台线程池里做，界面先用ID前缀显示，标题回来再刷新。
 *  2. **只做一次**：session 里已经有名字就不再生成（用 setTitleIfAbsent），
 *     用户手动改过的名字不会被模型的输出覆盖。
 *  3. **失败要有兜底**：模型调用失败（断网、API Key 错、本地模型还没热）时，
 *     用首条消息截断出一个标题，至少比一串随机数强。
 *  4. 并发去重：同一个会话的首条消息可能因为重试被触发两次，用 inFlight 集合挡住。
 */
@Component
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    /** 标题最多多少个字符 */
    private static final int MAX_TITLE_CHARS = 20;
    /** 喂给模型的用户消息最长截多少字符（标题只需要知道大意） */
    private static final int MAX_INPUT_CHARS = 600;

    private static final String SYSTEM_PROMPT = """
        你是会话标题生成器。根据用户的第一条消息，生成一个概括这次会话要做什么的短标题。

        要求：
        1. 不超过 12 个字（中文）或 6 个单词（英文），用与用户相同的语言
        2. 只输出标题本身：不要引号、不要书名号、不要句末标点、不要解释、不要换行
        3. 直接说做什么事，例如：修复登录超时、给配置加注释、排查内存泄漏、写单元测试
        """;

    private final SessionManager sessionManager;
    private final AdapterManager adapterManager;
    private final AppConfigStore configStore;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger counter = new AtomicInteger();

    /** 单线程守护线程池：标题生成很轻，串行还能避免和主对话抢本地模型的算力 */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-title");
        t.setDaemon(true);
        return t;
    });

    public SessionTitleService(SessionManager sessionManager, AdapterManager adapterManager,
                               AppConfigStore configStore) {
        this.sessionManager = sessionManager;
        this.adapterManager = adapterManager;
        this.configStore = configStore;
    }

    /**
     * 判断这个会话是不是"还没有名字"，需要生成
     */
    public boolean needsTitle(String sessionId) {
        return sessionManager.getSession(sessionId)
            .map(s -> s.name() == null || s.name().isBlank())
            .orElse(false);
    }

    /**
     * 异步生成会话标题（幂等：已有名字 / 正在生成 / 空消息都会直接返回）
     *
     * @param sessionId 会话ID
     * @param message   用户发出的第一条消息
     */
    public void generateAsync(String sessionId, String message) {
        if (sessionId == null || message == null || message.isBlank()) {
            return;
        }
        if (!needsTitle(sessionId)) {
            return;                        // 已经有名字（用户改过或已生成）
        }
        if (!inFlight.add(sessionId)) {
            return;                        // 已经有一次生成在进行中
        }
        final int id = counter.incrementAndGet();
        pool.submit(() -> {
            try {
                String title = generate(sessionId, message);
                if (title != null && !title.isBlank()) {
                    boolean ok = sessionManager.setTitleIfAbsent(sessionId, title);
                    log.info("[标题#{}] 会话 {} 标题生成{}: {}",
                        id, sessionId, ok ? "成功" : "被跳过(已有名字)", title);
                }
            } catch (Exception e) {
                log.warn("[标题#{}] 会话 {} 标题生成失败，改用消息摘要兜底: {}",
                    id, sessionId, e.getMessage());
                // 兜底：至少给一个人能看懂的名字，别让界面一直显示随机数
                sessionManager.setTitleIfAbsent(sessionId, fallbackTitle(message));
            } finally {
                inFlight.remove(sessionId);
            }
        });
    }

    /**
     * 真正去调模型生成标题。失败会抛异常，由调用方兜底。
     */
    private String generate(String sessionId, String message) {
        ModelAdapter adapter = adapterManager.getActiveAdapter();
        if (adapter == null) {
            throw new IllegalStateException("没有可用的模型适配器");
        }
        String model = currentModel(adapter);
        String input = message.length() > MAX_INPUT_CHARS
            ? message.substring(0, MAX_INPUT_CHARS) : message;

        // 标题任务要明确关掉"思考"。
        // 思考类模型（返回里同时有 content 和 reasoning_content）在 max_tokens 给小的时候，
        // 会把预算全花在 reasoning 上，content 直接返回空串，标题就生成不出来。
        // thinking={"type":"disabled"} 能让服务端 0 reasoning 直接出正文
        // （实测某思考模型：32 token 全被 reasoning 吃光 → 加了这个参数后只用 7 token 且正文非空）。
        // 支持该参数的服务会照做；不支持的会当成未知字段忽略，不影响调用。
        Map<String, Object> extra = Map.of("thinking", Map.of("type", "disabled"));

        ModelResponse resp = adapter.chatWithOptions(
            List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
            model, ThinkingLevel.LOW, null, extra, 128);

        String title = cleanTitle(resp == null ? null : resp.content());
        if (title == null || title.isBlank()) {
            // 兜底 1：正文为空但思考内容有货 —— 有些思考模型把结果写在 reasoning_content 里，
            // 或者思考过程里已经写出了"Title: xxx"。从里面抠一个出来。
            title = cleanTitle(titleFromReasoning(resp == null ? null : resp.reasoningContent()));
        }
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("模型返回了空标题");
        }
        return title;
    }

    /**
     * 从思考内容里抠标题。
     *
     * 两个策略：
     *   1. 思考过程里常见 "Title: xxx" / "标题：xxx" 这种自问自答，优先取它
     *   2. 都没有就取最后一行非空文本（思考模型的结论通常在末尾）
     */
    static String titleFromReasoning(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?i)(?:^|\\n)\\s*(?:title|标题)\\s*[:：]\\s*([^\\n。．]{1,40})")
            .matcher(reasoning);
        String last = null;
        while (m.find()) {
            last = m.group(1).trim();
        }
        if (last != null && !last.isBlank()) {
            return last;
        }
        String[] lines = reasoning.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 清洗模型输出：去掉引号、书名号、markdown 标记、句末标点、多余换行
     */
    static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // 只取第一行（模型有时会多输出一行解释）
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        // 常见前缀
        for (String prefix : new String[] {"标题：", "标题:", "Title:", "title:", "会话标题：", "会话标题:"}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
            }
        }
        // 去掉包裹的引号/书名号/markdown
        s = s.replaceAll("^[\\s\"'“”‘’《》<>#*`]+", "")
             .replaceAll("[\\s\"'“”‘’《》<>#*`]+$", "")
             .trim();
        // 去掉句末标点
        s = s.replaceAll("[。．.，,；;：:！!？?~～]+$", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_TITLE_CHARS) {
            s = s.substring(0, MAX_TITLE_CHARS);
        }
        return s;
    }

    /**
     * 兜底标题：拿首条消息的开头做摘要
     */
    static String fallbackTitle(String message) {
        String s = message.replaceAll("\\s+", " ").trim();
        // 去掉常见的客套话开头
        for (String prefix : new String[] {"帮我", "麻烦", "请", "你能", "能不能", "帮忙"}) {
            while (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
            }
        }
        if (s.isEmpty()) {
            return "新会话";
        }
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    /**
     * 取当前配置的模型名；local 模式回落到底座默认模型
     */
    @SuppressWarnings("unchecked")
    private String currentModel(ModelAdapter adapter) {
        Map<String, Object> saved = configStore.getMap("openai");
        Object m = saved.get("model");
        if (m instanceof String s && !s.isBlank()) {
            return s;
        }
        Object top = configStore.get("model", null);
        if (top instanceof String s && !s.isBlank()) {
            return s;
        }
        return "MiMo-V2.6-Distill-Qwen-9B";
    }
}
