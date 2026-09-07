package com.lioncode.core.session;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.model.adapter.ModelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器
 * 
 * 管理Agent对话会话的生命周期。
 * 强制绑定工作区：未选择工作区，不能创建和使用对话会话。
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** 活跃会话映射 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     * 
     * @param workspaceId 绑定的工作区ID（强制要求）
     * @param mode 工作模式
     * @return 新会话
     */
    public Session createSession(String workspaceId, AgentMode mode) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalStateException("未绑定工作区，无法创建会话。请先选择工作区目录。");
        }

        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, workspaceId, mode, Instant.now());
        sessions.put(sessionId, session);
        log.info("新会话已创建: {} - 工作区: {}, 模式: {}", sessionId, workspaceId, mode.getCode());
        return session;
    }

    /**
     * 获取会话
     */
    public Optional<Session> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 获取全部活跃会话
     */
    public List<Session> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 销毁会话
     */
    public void destroySession(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("会话已销毁: {}", sessionId);
        }
    }

    /**
     * 会话数据类
     */
    public record Session(
        String sessionId,
        String workspaceId,
        AgentMode mode,
        Instant createdAt
    ) {}
}
