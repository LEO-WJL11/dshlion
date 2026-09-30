package com.lioncode.queue;

import com.lioncode.core.agent.AgentControlManager;
import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 会话消息调度器（消息队列的消费者）
 * 
 * 核心职责：
 * 1. 同会话消息串行处理——每个会话同一时刻只有一个Worker在处理，
 *    彻底解决"两个请求同时打一个会话搞乱对话历史"的并发问题
 * 2. 消费MessageQueue：FIFO + Steer插队（Steer先中断当前任务再插队）
 * 3. 结果通过CompletableFuture回填给HTTP请求线程
 */
@Component
public class SessionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SessionDispatcher.class);

    private final MessageQueue messageQueue;
    private final AgentLoop agentLoop;
    private final AgentControlManager agentControl;
    private final SessionManager sessionManager;

    /** 正在处理的会话集合（保证每会话单Worker） */
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    /** 流式处理占用的会话（流式接口不与队列混跑，但需互斥） */
    private final Set<String> streamingSessions = ConcurrentHashMap.newKeySet();

    /** Worker线程池（每会话一个临时Worker，空闲自动退出） */
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lion-session-worker");
        t.setDaemon(true);
        return t;
    });

    public SessionDispatcher(MessageQueue messageQueue, AgentLoop agentLoop,
                             AgentControlManager agentControl, SessionManager sessionManager) {
        this.messageQueue = messageQueue;
        this.agentLoop = agentLoop;
        this.agentControl = agentControl;
        this.sessionManager = sessionManager;
    }

    /**
     * 提交消息并返回结果Future（同步HTTP接口用future.get等待）
     * 
     * @param steer true=插队模式：先中断当前任务，本消息优先执行
     */
    public CompletableFuture<String> submit(String sessionId, String userMessage,
                                            String model, ThinkingLevel thinkingLevel,
                                            boolean steer) {
        if (steer) {
            // Steer语义：中断正在执行的任务（该任务会以"已停止"回复它的调用方），
            // 本消息以最高优先级插到队首
            log.info("Steer插队: 中断会话 {} 当前任务", sessionId);
            agentControl.stop(sessionId);
        }
        MessageQueue.QueuedMessage message = messageQueue.submit(
            sessionId, userMessage, steer ? 100 : 0, steer, model,
            thinkingLevel != null ? thinkingLevel.name() : ThinkingLevel.MEDIUM.name());
        // 队列满被拒时 future 已经带着异常完成了，不必再起一个只会空转 30 秒的 worker
        if (!message.future().isDone()) {
            startWorker(sessionId);
        }
        return message.future();
    }

    /**
     * 会话是否繁忙（有Worker在跑或有排队消息）
     */
    public boolean isBusy(String sessionId) {
        return activeSessions.contains(sessionId)
            || streamingSessions.contains(sessionId)
            || messageQueue.pendingCount(sessionId) > 0;
    }

    /**
     * 流式接口占用会话（与队列处理互斥）
     */
    public boolean tryAcquireStream(String sessionId) {
        if (isBusy(sessionId)) {
            return false;
        }
        return streamingSessions.add(sessionId);
    }

    public void releaseStream(String sessionId) {
        streamingSessions.remove(sessionId);
    }

    /**
     * 启动该会话的Worker（已有Worker在跑则跳过）
     */
    private void startWorker(String sessionId) {
        if (!activeSessions.add(sessionId)) {
            return; // 已有Worker在处理该会话
        }
        workers.submit(() -> {
            try {
                drain(sessionId);
            } finally {
                activeSessions.remove(sessionId);
                // 退出前复查：期间可能又有新消息入队
                if (messageQueue.pendingCount(sessionId) > 0) {
                    startWorker(sessionId);
                }
            }
        });
    }

    /**
     * Worker主循环：逐条消费该会话队列，空闲30秒自动退出
     */
    private void drain(String sessionId) {
        while (true) {
            MessageQueue.QueuedMessage message;
            try {
                // 空闲超时返回null → Worker退出，下次提交时再启动
                message = messageQueue.poll(sessionId, 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (message == null) {
                return; // 队列空闲，退出Worker
            }

            try {
                // 处理时取会话当前生效模式（运行期间可能被切换）
                AgentMode mode = sessionManager.getEffectiveMode(sessionId);
                ThinkingLevel level = parseLevel(message.thinkingLevel());
                String result = agentLoop.processMessage(
                    sessionId, message.content(), mode, level, message.model());
                // 归一化 null：complete(null) 会让 future.get() 返回 null，
                // 调用方（ChatController）再把它塞进 ApiResponse.data，前端拿到 null 显示空白
                message.future().complete(result == null ? "" : result);
            } catch (Exception e) {
                log.error("消息处理失败: {} (会话: {})", message.id(), sessionId, e);
                message.future().completeExceptionally(e);
            }
        }
    }

    private ThinkingLevel parseLevel(String name) {
        try {
            return ThinkingLevel.valueOf(name);
        } catch (Exception e) {
            return ThinkingLevel.MEDIUM;
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
