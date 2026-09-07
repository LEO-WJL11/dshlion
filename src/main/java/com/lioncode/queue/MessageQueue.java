package com.lioncode.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * 消息队列
 * 
 * 消息默认FIFO排队；支持Steer插队，中断当前任务优先处理用户消息。
 */
@Component
public class MessageQueue {

    private static final Logger log = LoggerFactory.getLogger(MessageQueue.class);

    /** FIFO队列 */
    private final BlockingQueue<QueueMessage> fifoQueue = new LinkedBlockingQueue<>(1000);

    /** 优先级队列（Steer插队用） */
    private final BlockingQueue<QueueMessage> priorityQueue = new PriorityBlockingQueue<>(100, 
        (a, b) -> Integer.compare(b.priority(), a.priority()));

    /**
     * 普通入队（FIFO）
     */
    public void enqueue(QueueMessage message) {
        fifoQueue.offer(message);
        log.debug("消息入队: {}", message.id());
    }

    /**
     * 优先入队（Steer插队）
     */
    public void enqueuePriority(QueueMessage message) {
        priorityQueue.offer(message);
        log.info("优先消息入队（Steer）: {}", message.id());
    }

    /**
     * 出队（优先级队列优先）
     */
    public QueueMessage dequeue() {
        QueueMessage msg = priorityQueue.poll();
        if (msg != null) return msg;
        return fifoQueue.poll();
    }

    /**
     * 获取队列大小
     */
    public int size() {
        return fifoQueue.size() + priorityQueue.size();
    }

    /**
     * 队列消息记录
     */
    public record QueueMessage(
        String id,
        String sessionId,
        String content,
        int priority,
        boolean isSteer
    ) {}
}
