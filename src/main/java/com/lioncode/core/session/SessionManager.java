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
     * 会话持久化。
     * 用 setter 注入而不是构造器注入：SessionPersistence 启动时要读磁盘，
     * 而它自己的 @PostConstruct 依赖工作区路径，构造器互相注入容易踩启动顺序。
     */
    private SessionPersistence persistence;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPersistence(SessionPersistence persistence) {
        this.persistence = persistence;
    }

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
        // name 留空：等用户在会话里发出第一条消息后由 SessionTitleService 生成标题
        AgentMode effective = AgentMode.normalize(mode);
        Session session = new Session(sessionId, workspaceId, effective, Instant.now(), null);
        sessions.put(sessionId, session);
        modeOverrides.put(sessionId, effective);
        log.info("新会话已创建: {} - 工作区: {}, 模式: {}", sessionId, workspaceId, effective.getCode());
        return session;
    }

    /**
     * 恢复历史会话（启动时从磁盘加载，跳过工作区强制检查）
     */
    public Session restoreSession(String sessionId, String workspaceId, AgentMode mode, Instant createdAt) {
        return restoreSession(sessionId, workspaceId, mode, createdAt, null);
    }

    /**
     * 恢复历史会话（带会话名称）
     */
    public Session restoreSession(String sessionId, String workspaceId, AgentMode mode, Instant createdAt,
                                  String name) {
        // 老会话里可能是 PTC/CREATIVE（现在不给用户选了）—— 加载时归一成标准模式
        AgentMode effective = AgentMode.normalize(mode);
        if (effective != mode) {
            log.info("会话 {} 的模式 {} 已不再开放，按标准模式恢复", sessionId,
                mode == null ? "null" : mode.getCode());
        }
        Session session = new Session(sessionId, workspaceId, effective, createdAt, name);
        sessions.put(sessionId, session);
        // 这里必须是 effective：modeOverrides 的优先级比 Session.mode() 高，
        // 存归一前的值等于让 PTC/CREATIVE 从后门继续生效。
        modeOverrides.put(sessionId, effective);
        log.info("会话已从磁盘恢复: {} - 工作区: {}, 模式: {}, 名称: {}",
            sessionId, workspaceId, effective.getCode(), name == null ? "(未命名)" : name);
        return session;
    }

    /**
     * 重命名会话（用户手动改，或模型自动生成）
     *
     * Session 是 record（不可变），所以这里造一个新的替换掉旧的，并落盘。
     *
     * @param sessionId 会话ID
     * @param name      新名称；空白字符串视为清空
     * @return 是否成功
     */
    public synchronized boolean renameSession(String sessionId, String name) {
        Session old = sessions.get(sessionId);
        if (old == null) {
            log.warn("重命名失败，会话不存在: {}", sessionId);
            return false;
        }
        String cleaned = name == null ? null : name.trim();
        if (cleaned != null && cleaned.isBlank()) {
            cleaned = null;
        }
        if (cleaned != null && cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        Session updated = new Session(old.sessionId(), old.workspaceId(), old.mode(),
            old.createdAt(), cleaned);
        sessions.put(sessionId, updated);
        if (persistence != null) {
            persistence.saveSession(updated);
        }
        log.info("会话已重命名: {} -> {}", sessionId, cleaned);
        return true;
    }

    /**
     * 把会话换到另一个工作区（界面上"给这个对话选工作区"用的）。
     *
     * <p>只改绑定关系，不动已有对话内容 —— 历史里已经产生的路径是绝对路径，
     * 换工作区只影响**之后**的相对路径解析。
     *
     * @return 是否成功（会话不存在或工作区为空时返回 false）
     */
    public synchronized boolean moveSessionToWorkspace(String sessionId, String workspaceId) {
        Session old = sessions.get(sessionId);
        if (old == null) {
            log.warn("换工作区失败，会话不存在: {}", sessionId);
            return false;
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            log.warn("换工作区失败，工作区为空: {}", sessionId);
            return false;
        }
        if (workspaceId.equals(old.workspaceId())) {
            return true;   // 没变，直接当成功
        }
        Session updated = new Session(old.sessionId(), workspaceId, old.mode(),
            old.createdAt(), old.name());
        sessions.put(sessionId, updated);
        if (persistence != null) {
            persistence.saveSession(updated);
        }
        log.info("会话已换工作区: {} → {}", sessionId, workspaceId);
        return true;
    }

    /**
     * 仅在会话还没有名字时写入（模型自动生成用，避免覆盖用户自己改的名字）
     */
    public synchronized boolean setTitleIfAbsent(String sessionId, String name) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            return false;
        }
        if (s.name() != null && !s.name().isBlank()) {
            return false;   // 用户已经手动命名过，或者已经生成过
        }
        return renameSession(sessionId, name);
    }

    /**
     * 运行时切换会话的工作模式（立即生效，后续消息按新模式处理）
     */
    public boolean updateMode(String sessionId, AgentMode mode) {
        mode = AgentMode.normalize(mode);
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
     *
     * name：会话标题。新建时为 null（界面上先显示会话ID前缀），
     *       等用户在会话里发出第一条消息后由模型生成，用户也可以随时手动改。
     *       老版本存下来的会话文件里没有这个字段，反序列化时会是 null，属于正常情况。
     */
    public record Session(
        String sessionId,
        String workspaceId,
        AgentMode mode,
        Instant createdAt,
        String name
    ) {
        /** 界面展示用：有名字用名字，没有就退回ID前缀 */
        public String displayName() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return "会话 " + (sessionId == null ? "?" :
                (sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId));
        }
    }
}
