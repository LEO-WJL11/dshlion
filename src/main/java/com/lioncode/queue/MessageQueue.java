package com.lioncode.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息队列（按会话隔离的优先级队列）
 * 
 * 每个会话一条队列，由SessionDispatcher消费者串行处理：
 * - 同会话消息严格FIFO，保证对话历史不会被并发写乱
 * - Steer（用户插队指令）优先级最高：先中断当前任务再插到队首
 * - 消息携带CompletableFuture，处理完成后回填结果
 */
@Component
public class MessageQueue {

    private static final Logger log = LoggerFactory.getLogger(MessageQueue.class);

    /** 会话ID -> 该会话的优先级消息队列 */
    private final Map<String, BlockingQueue<QueuedMessage>> sessionQueues = new ConcurrentHashMap<>();

    /** 全局消息序号（FIFO次序依据） */
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 提交消息（普通FIFO）
     * 
     * @return 携带Future的队列消息，处理完成后future完成
     */
    public QueuedMessage submit(String sessionId, String content, int priority, boolean isSteer,
                                String model, String thinkingLevel) {
        QueuedMessage message = new QueuedMessage(
            "msg_" + sequence.incrementAndGet(), sessionId, content,
            priority, isSteer, model, thinkingLevel, new CompletableFuture<>());
        queueOf(sessionId).offer(message);
        log.debug("消息入队: {} (会话: {}, steer: {})", message.id(), sessionId, isSteer);
        return message;
    }

    /**
     * 取出会话的下一条消息（阻塞直到有消息）
     */
    public QueuedMessage take(String sessionId) throws InterruptedException {
        return queueOf(sessionId).take();
    }

    /**
     * 取出会话的下一条消息（带超时，超时返回null，供Worker空闲退出）
     */
    public QueuedMessage poll(String sessionId, long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
        return queueOf(sessionId).poll(timeout, unit);
    }

    /**
     * 会话队列中待处理的消息数
     */
    public int pendingCount(String sessionId) {
        BlockingQueue<QueuedMessage> queue = sessionQueues.get(sessionId);
        return queue == null ? 0 : queue.size();
    }

    /**
     * 全部待处理消息数
     */
    public int size() {
        return sessionQueues.values().stream().mapToInt(BlockingQueue::size).sum();
    }

    private BlockingQueue<QueuedMessage> queueOf(String sessionId) {
        return sessionQueues.computeIfAbsent(sessionId, k -> new PriorityBlockingQueue<>(64,
            // Steer/高优先级在前，同优先级按入队序号（FIFO）
            (a, b) -> {
                int p = Integer.compare(b.priority(), a.priority());
                if (p != 0) return p;
                return Long.compare(a.seq(), b.seq());
            }));
    }

    /**
     * 队列消息
     */
    public record QueuedMessage(
        String id,
        String sessionId,
        String content,
        int priority,
        boolean isSteer,
        String model,
        String thinkingLevel,
        CompletableFuture<String> future
    ) {
        private long seq() {
            try {
                return Long.parseLong(id.substring(4));
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
