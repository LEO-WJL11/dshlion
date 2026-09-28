package com.lioncode.core.question;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 向用户提问的服务
 *
 * 模型调 {@code ask_user} 工具时，实际是走这里：
 *   1. 把问题登记成"待回答"，前端轮询 {@code GET /api/questions/pending} 就能看到并弹出输入框；
 *   2. 工具所在线程**阻塞等待**（最多 timeoutSeconds 秒），等用户在界面上回答；
 *   3. 用户 {@code POST /api/questions/answer} 提交后，这里的闩锁放行，答案作为工具结果回到模型。
 *
 * 为什么可以阻塞：AgentLoop 跑在 SessionDispatcher 的会话 worker 线程上，
 * 同一个会话本来就串行，等用户回答不会拖住别的会话。
 *
 * 三种结束方式：
 *   - 用户回答     → 返回答案
 *   - 超时         → 返回"用户未在规定时间内回答"，让模型自己决定怎么办（别死等）
 *   - 会话被中止    → 取消登记，工具立刻返回"提问已取消"
 */
@Component
public class UserQuestionService {

    private static final Logger log = LoggerFactory.getLogger(UserQuestionService.class);

    /** 默认等待时长（秒）。要比 ChatController 那边 10 分钟的等待上限小得多 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** 轮询间隔：等待期间用它定期醒来，检查问题是否被取消 */
    private static final long POLL_MS = 400L;

    /** 一条待回答的问题 */
    public record Pending(
        String id,
        String sessionId,
        String question,
        List<String> options,
        long askedAt
    ) {}

    /** 一次提问的等待状态 */
    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String answer;
        volatile boolean cancelled;
    }

    /** 待回答的问题：questionId -> Pending */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** 等待中的提问：questionId -> Waiter */
    private final Map<String, Waiter> waiters = new ConcurrentHashMap<>();

    /**
     * 提问并等用户回答（阻塞）。
     *
     * @param sessionId      会话 ID
     * @param question       问题正文
     * @param options        可选项；为空表示让用户自由输入
     * @param timeoutSeconds 最长等待秒数（<=0 用默认值）
     * @return 结果，包含答案和"是回答还是超时/取消"
     */
    public Result ask(String sessionId, String question, List<String> options, int timeoutSeconds) {
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> opts = options == null ? List.of() : new ArrayList<>(options);

        Pending p = new Pending(id, sessionId, question, opts, System.currentTimeMillis());
        Waiter w = new Waiter();
        pending.put(id, p);
        waiters.put(id, w);
        log.info("向用户提问: 会话={}, 问题={}, 选项={}, 等待上限={}秒",
            sessionId, abbreviate(question), opts, timeout);

        try {
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (w.latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                    break;                                  // 用户答了
                }
                if (w.cancelled) {
                    return new Result(Status.CANCELLED, null, "（提问已取消：会话被中止）");
                }
            }
            if (w.answer != null && !w.answer.isBlank()) {
                log.info("用户已回答: 会话={}, 问题ID={}, 答案={}", sessionId, id, abbreviate(w.answer));
                return new Result(Status.ANSWERED, w.answer, w.answer);
            }
            log.info("等待用户回答超时: 会话={}, 问题ID={}", sessionId, id);
            return new Result(Status.TIMEOUT, null,
                "（用户 " + timeout + " 秒内没有回答。请不要再重复提问，"
                + "先基于现有信息给出你能做的部分，并在回复里说明还缺什么。）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.CANCELLED, null, "（提问被中断）");
        } finally {
            pending.remove(id);
            waiters.remove(id);
        }
    }

    /**
     * 用户提交回答
     *
     * @param questionId 问题 ID
     * @param answer     答案正文
     * @return 是否命中了一个正在等待的问题
     */
    public boolean answer(String questionId, String answer) {
        Waiter w = waiters.get(questionId);
        if (w == null) {
            return false;                                   // 超时过了或者 ID 不对
        }
        w.answer = answer == null ? "" : answer.trim();
        w.latch.countDown();
        return true;
    }

    /**
     * 取某会话当前待回答的问题（前端轮询用）
     */
    public Optional<Pending> pendingOf(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return pending.values().stream()
            .filter(p -> sessionId.equals(p.sessionId()))
            .findFirst();
    }

    /**
     * 取消某会话上所有等待中的提问（会话被手动停止时调用，别让 worker 线程一直挂着）
     *
     * @return 取消掉的条数
     */
    public int cancelSession(String sessionId) {
        int n = 0;
        for (Pending p : pending.values()) {
            if (sessionId.equals(p.sessionId())) {
                Waiter w = waiters.get(p.id());
                if (w != null) {
                    w.cancelled = true;
                    w.latch.countDown();
                    n++;
                }
            }
        }
        if (n > 0) {
            log.info("已取消会话 {} 上 {} 个等待中的提问", sessionId, n);
        }
        return n;
    }

    /** 当前待回答总数（诊断用） */
    public int pendingCount() {
        return pending.size();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /** 提问的结束状态 */
    public enum Status {
        /** 用户回答了 */
        ANSWERED,
        /** 等超时了 */
        TIMEOUT,
        /** 被取消（会话中止） */
        CANCELLED
    }

    /**
     * 提问结果
     *
     * @param status 结束状态
     * @param answer 用户答案（仅 ANSWERED 时有值）
     * @param text   直接回给模型看的文本
     */
    public record Result(Status status, String answer, String text) {}

    /** 给前端的状态快照 */
    public Map<String, Object> toMap(Pending p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("sessionId", p.sessionId());
        m.put("question", p.question());
        m.put("options", p.options());
        m.put("askedAt", p.askedAt());
        return m;
    }
}
