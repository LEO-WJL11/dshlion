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

    /** 裁剪结果（给工具返回值/界面用） */
    public record PruneResult(int total, int removed, int kept, List<String> preview, boolean dryRun) {}

    /**
     * 裁剪会话历史：只保留最近 {@code keepLast} 条，更早的**真删**（并从磁盘上落一次）。
     *
     * <p>【和"压缩"的区别】压缩（{@code ContextCompressor}）是塞不下时把中间折叠成摘要，
     * 只影响那一次请求、磁盘上的原文还在；这里是模型明确说"前面那些不要了"，
     * 所以删就是删（用户要的就是这个：真删掉才省预填充）。</p>
     *
     * <p>【两个保护，别删出坏消息】</p>
     * <ol>
     *   <li><b>不越过工具调用的配对边界</b>：如果切点上正好是某条 tool 结果，
     *       而它的 assistant(tool_calls) 被切在外面，就会留下孤儿 tool 消息 ——
     *       有些 API 直接 400。所以切点会往后挪到"安全起点"（user 消息，或一轮的开头）。</li>
     *   <li><b>system 消息永不删</b>：它们是会话的元信息（比如"这条会话用的是哪个工作区"），
     *       删了会莫名其妙地丢上下文。</li>
     * </ol>
     *
     * @param dryRun true = 只算不删（让模型先看看会删什么）
     */
    public PruneResult pruneHistory(String sessionId, int keepLast, boolean dryRun) {
        if (sessionId == null) {
            return new PruneResult(0, 0, 0, List.of(), dryRun);
        }
        List<ConversationMessage> messages = conversations.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return new PruneResult(0, 0, 0, List.of(), dryRun);
        }
        int total = messages.size();
        int keep = Math.max(2, keepLast);
        if (total <= keep) {
            return new PruneResult(total, 0, total, List.of(), dryRun);
        }

        int cut = total - keep;                       // 想从这里开始留
        cut = safeCut(messages, cut);                 // 挪到安全边界
        if (cut <= 0) {
            return new PruneResult(total, 0, total, List.of(), dryRun);
        }

        List<String> preview = new ArrayList<>();
        for (int i = 0; i < cut && preview.size() < 12; i++) {
            ConversationMessage m = messages.get(i);
            preview.add(describe(m));
        }
        if (cut > preview.size()) {
            preview.add("…… 还有 " + (cut - preview.size()) + " 条");
        }

        if (dryRun) {
            return new PruneResult(total, cut, total - cut, preview, true);
        }

        List<ConversationMessage> kept = new ArrayList<>(messages.subList(cut, total));
        conversations.put(sessionId, new java.util.concurrent.CopyOnWriteArrayList<>(kept));
        saveToDisk(sessionId);
        log.info("会话 {} 已裁剪: 原 {} 条 → 留 {} 条（删 {} 条）", sessionId, total, kept.size(), cut);
        return new PruneResult(total, cut, kept.size(), preview, false);
    }

    /**
     * 把切点挪到一个"安全边界"：不能落在某一轮工具调用的中间。
     *
     * <p>判定规则：从候选切点向后找第一条 role=user 的消息作为起点；
     * 找不到就往前退（宁可少删一点，也不能删出孤儿 tool 消息）。</p>
     */
    private int safeCut(List<ConversationMessage> messages, int cut) {
        for (int i = cut; i < messages.size(); i++) {
            String role = messages.get(i).role();
            if ("user".equals(role)) {
                return i;
            }
        }
        // 后面没有 user 消息了（比如全是工具轮的尾巴）：往前退到最近一条 user 之后
        for (int i = cut - 1; i > 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 一条消息的一行摘要（"user: 帮我把登录改成…"这种） */
    private static String describe(ConversationMessage m) {
        String role = switch (m.role() == null ? "" : m.role()) {
            case "user" -> "用户";
            case "assistant" -> m.toolCalls() != null && !m.toolCalls().isEmpty()
                ? "模型（调工具 " + m.toolCalls().size() + " 个）" : "模型";
            case "tool" -> "工具结果" + (m.toolName() == null ? "" : "(" + m.toolName() + ")");
            case "system" -> "系统";
            default -> m.role();
        };
        String text = m.content() == null ? "" : m.content().replaceAll("\\s+", " ").trim();
        if (text.length() > 60) {
            text = text.substring(0, 60) + "…";
        }
        return role + (text.isEmpty() ? "" : "：" + text);
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
