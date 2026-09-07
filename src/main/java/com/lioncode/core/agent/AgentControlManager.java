package com.lioncode.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent运行控制管理器
 * 
 * 提供每个会话的暂停/继续/停止控制：
 * - AgentLoop每轮工具调用循环前调用checkControl检查状态
 * - 暂停：循环阻塞等待，直到前端调用继续
 * - 停止：抛出AgentStoppedException，AgentLoop捕获后中止任务
 * 
 * 状态是易失的：每次新任务开始（processMessage开头）自动reset。
 */
@Component
public class AgentControlManager {

    private static final Logger log = LoggerFactory.getLogger(AgentControlManager.class);

    /** 运行状态 */
    public enum State { RUNNING, PAUSED, STOPPED }

    /** 会话ID -> 控制状态 */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 新任务开始：清除该会话之前的暂停/停止状态
     */
    public void reset(String sessionId) {
        states.remove(sessionId);
    }

    /**
     * 暂停
     */
    public void pause(String sessionId) {
        states.put(sessionId, State.PAUSED);
        log.info("Agent已暂停: {}", sessionId);
    }

    /**
     * 继续
     */
    public void resume(String sessionId) {
        State prev = states.put(sessionId, State.RUNNING);
        log.info("Agent已继续: {} (之前状态: {})", sessionId, prev);
    }

    /**
     * 停止（暂停中也可以直接停止）
     */
    public void stop(String sessionId) {
        states.put(sessionId, State.STOPPED);
        log.info("Agent已请求停止: {}", sessionId);
    }

    /**
     * 获取当前状态
     */
    public State getState(String sessionId) {
        return states.getOrDefault(sessionId, State.RUNNING);
    }

    /**
     * 控制检查：AgentLoop每轮循环前调用
     * 
     * - PAUSED：阻塞等待直到继续
     * - STOPPED：抛出AgentStoppedException
     * - RUNNING：直接返回
     */
    public void checkControl(String sessionId) {
        while (getState(sessionId) == State.PAUSED) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (getState(sessionId) == State.STOPPED) {
            throw new AgentStoppedException("任务已被手动停止");
        }
    }

    /**
     * 停止信号异常
     */
    public static class AgentStoppedException extends RuntimeException {
        public AgentStoppedException(String message) {
            super(message);
        }
    }
}
