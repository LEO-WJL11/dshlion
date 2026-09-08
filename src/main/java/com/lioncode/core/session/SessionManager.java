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

    /** 会话模式覆盖：会话ID -> 当前生效模式（运行时切换用） */
    private final Map<String, AgentMode> modeOverrides = new ConcurrentHashMap<>();

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
        modeOverrides.put(sessionId, mode);
        log.info("新会话已创建: {} - 工作区: {}, 模式: {}", sessionId, workspaceId, mode.getCode());
        return session;
    }

    /**
     * 恢复历史会话（启动时从磁盘加载，跳过工作区强制检查）
     */
    public Session restoreSession(String sessionId, String workspaceId, AgentMode mode, Instant createdAt) {
        Session session = new Session(sessionId, workspaceId, mode, createdAt);
        sessions.put(sessionId, session);
        modeOverrides.put(sessionId, mode);
        log.info("会话已从磁盘恢复: {} - 工作区: {}, 模式: {}", sessionId, workspaceId, mode.getCode());
        return session;
    }

    /**
     * 运行时切换会话的工作模式（立即生效，后续消息按新模式处理）
     */
    public boolean updateMode(String sessionId, AgentMode mode) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            log.warn("切换模式失败，会话不存在: {}", sessionId);
            return false;
        }
        modeOverrides.put(sessionId, mode);
        log.info("会话模式已切换: {} -> {}", sessionId, mode.getCode());
        return true;
    }

    /**
     * 获取会话当前生效的工作模式（含运行时切换覆盖）
     */
    public AgentMode getEffectiveMode(String sessionId) {
        AgentMode override = modeOverrides.get(sessionId);
        if (override != null) {
            return override;
        }
        return getSession(sessionId).map(Session::mode).orElse(AgentMode.STANDARD);
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
