package com.lioncode.core.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话持久化管理器
 * 
 * 负责会话元数据的持久化存储和恢复。
 * 会话持久化绑定对应工作区目录。
 */
@Component
public class SessionPersistence {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistence.class);
    private static final ObjectMapper mapper = createMapper();

    @Value("${lion.workspace.default-path:${user.home}/lion-code-workspace}")
    private String workspacePath;

    private Path sessionsDir;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @PostConstruct
    public void init() {
        sessionsDir = Path.of(workspacePath, ".lioncode", "sessions");
        try {
            Files.createDirectories(sessionsDir);
            log.info("会话持久化目录已初始化: {}", sessionsDir);
        } catch (IOException e) {
            log.error("无法创建会话持久化目录", e);
        }
    }

    /**
     * 保存会话元数据
     */
    public void saveSession(SessionManager.Session session) {
        try {
            Path filePath = sessionsDir.resolve(session.sessionId() + ".json");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(session);
            Files.writeString(filePath, json);
            log.debug("会话元数据已保存: {}", session.sessionId());
        } catch (IOException e) {
            log.error("保存会话元数据失败: {}", session.sessionId(), e);
        }
    }

    /**
     * 加载会话元数据
     */
    public Optional<SessionManager.Session> loadSession(String sessionId) {
        Path filePath = sessionsDir.resolve(sessionId + ".json");
        if (!Files.exists(filePath)) return Optional.empty();

        try {
            String json = Files.readString(filePath);
            SessionManager.Session session = mapper.readValue(json, SessionManager.Session.class);
            return Optional.of(session);
        } catch (IOException e) {
            log.error("加载会话元数据失败: {}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * 获取所有已保存的会话
     */
    public List<SessionManager.Session> loadAllSessions() {
        List<SessionManager.Session> sessions = new ArrayList<>();
        try {
            if (!Files.exists(sessionsDir)) return sessions;
            
            Files.list(sessionsDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        String json = Files.readString(file);
                        SessionManager.Session session = mapper.readValue(json, SessionManager.Session.class);
                        sessions.add(session);
                    } catch (IOException e) {
                        log.warn("跳过损坏的会话文件: {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.error("加载会话列表失败", e);
        }
        return sessions;
    }

    /**
     * 删除会话持久化数据
     */
    public void deleteSession(String sessionId) {
        try {
            Path filePath = sessionsDir.resolve(sessionId + ".json");
            Files.deleteIfExists(filePath);
            log.info("会话持久化数据已删除: {}", sessionId);
        } catch (IOException e) {
            log.error("删除会话持久化数据失败: {}", sessionId, e);
        }
    }
}
