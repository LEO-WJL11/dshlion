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
 * 对话历史管理器
 * 
 * 管理每个会话的完整对话历史，支持：
 * - 消息追加和查询
 * - 持久化到磁盘
 * - 从磁盘恢复
 * - 上下文窗口管理（截断过长历史）
 */
@Component
public class ConversationHistory {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistory.class);
    private static final ObjectMapper mapper = createMapper();

    @Value("${lion.workspace.default-path:${user.home}/lion-code-workspace}")
    private String workspacePath;

    /** 会话消息存储：会话ID -> 消息列表（使用CopyOnWriteArrayList保证线程安全） */
    private final Map<String, List<ConversationMessage>> conversations = new ConcurrentHashMap<>();

    private Path historyDir;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @PostConstruct
    public void init() {
        historyDir = Path.of(workspacePath, ".lioncode", "conversations");
        try {
            Files.createDirectories(historyDir);
            log.info("对话历史存储目录已初始化: {}", historyDir);
            // 启动时自动恢复所有已保存的对话历史
            int restored = 0;
            for (String savedId : getSavedSessionIds()) {
                if (loadFromDisk(savedId)) {
                    restored++;
                }
            }
            log.info("对话历史已从磁盘恢复: {} 个会话", restored);
        } catch (IOException e) {
            log.error("无法创建对话历史目录", e);
        }
    }

    /**
     * 添加消息到会话（线程安全），并立即持久化到磁盘
     */
    public void addMessage(ConversationMessage message) {
        conversations.computeIfAbsent(message.sessionId(), 
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(message);
        log.debug("消息已添加到会话 {}: [{}] {}", message.sessionId(), 
            message.role(), truncate(message.content(), 50));
        // 自动持久化，保证重启/刷新后对话不丢失
        saveToDisk(message.sessionId());
    }

    /**
     * 获取会话的完整历史
     */
    public List<ConversationMessage> getHistory(String sessionId) {
        return List.copyOf(conversations.getOrDefault(sessionId, List.of()));
    }

    /**
     * 获取最近N条消息
     */
    public List<ConversationMessage> getRecentMessages(String sessionId, int count) {
        List<ConversationMessage> messages = conversations.getOrDefault(sessionId, List.of());
        int start = Math.max(0, messages.size() - count);
        return List.copyOf(messages.subList(start, messages.size()));
    }

    /**
     * 获取会话消息数量
     */
    public int getMessageCount(String sessionId) {
        return conversations.getOrDefault(sessionId, List.of()).size();
    }

    /**
     * 清空会话历史
     */
    public void clearHistory(String sessionId) {
        conversations.remove(sessionId);
        log.info("会话历史已清空: {}", sessionId);
    }

    /**
     * 持久化会话历史到磁盘
     */
    public void saveToDisk(String sessionId) {
        List<ConversationMessage> messages = conversations.get(sessionId);
        if (messages == null || messages.isEmpty()) return;

        try {
            Path filePath = historyDir.resolve(sessionId + ".json");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            Files.writeString(filePath, json);
            log.info("会话历史已保存: {} ({}条消息)", sessionId, messages.size());
        } catch (IOException e) {
            log.error("保存会话历史失败: {}", sessionId, e);
        }
    }

    /**
     * 从磁盘加载会话历史
     */
    public boolean loadFromDisk(String sessionId) {
        Path filePath = historyDir.resolve(sessionId + ".json");
        if (!Files.exists(filePath)) return false;

        try {
            String json = Files.readString(filePath);
            List<ConversationMessage> messages = mapper.readValue(json, 
                new TypeReference<List<ConversationMessage>>() {});
            conversations.put(sessionId, new java.util.concurrent.CopyOnWriteArrayList<>(messages));
            log.info("会话历史已从磁盘加载: {} ({}条消息)", sessionId, messages.size());
            return true;
        } catch (IOException e) {
            log.error("加载会话历史失败: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 删除会话的磁盘历史文件
     */
    public void deleteFromDisk(String sessionId) {
        try {
            Path filePath = historyDir.resolve(sessionId + ".json");
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.info("会话历史文件已删除: {}", sessionId);
            }
        } catch (IOException e) {
            log.error("删除会话历史文件失败: {}", sessionId, e);
        }
    }

    /**
     * 获取所有已保存的会话ID
     */
    public List<String> getSavedSessionIds() {
        try {
            if (!Files.exists(historyDir)) return List.of();
            return Files.list(historyDir)
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> p.getFileName().toString().replace(".json", ""))
                .toList();
        } catch (IOException e) {
            log.error("获取已保存会话列表失败", e);
            return List.of();
        }
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
