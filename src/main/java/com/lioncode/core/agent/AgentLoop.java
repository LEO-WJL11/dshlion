package com.lioncode.core.agent;

import com.lioncode.core.event.EventStore;
import com.lioncode.core.event.LionEvent;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.ConversationMessage;
import com.lioncode.core.session.SessionManager;
import com.lioncode.model.adapter.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent主循环
 * 
 * 核心运行时：负责接收用户消息、调用模型、解析工具调用、
 * 执行工具、汇总结果。与业务插件完全解耦。
 * 
 * 核心特性：
 * - 流式工具执行：识别到工具调用块就调度执行，不需要等待模型完整输出
 * - 事件溯源：完整记录每一轮思考、工具调用、返回结果
 * - 工具调用循环：自动处理多轮工具调用直到模型给出最终答案
 * - 异常隔离：工具执行异常不会导致整个Agent循环崩溃
 * - 多格式支持：同时支持XML和JSON格式的工具调用解析
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final EventStore eventStore;
    private final PluginRegistry pluginRegistry;
    private final ConversationHistory conversationHistory;
    private final AdapterManager adapterManager;
    private final AgentControlManager agentControl;
    private final SessionManager sessionManager;
    private final com.lioncode.core.workspace.WorkspaceManager workspaceManager;

    /** 每个会话的系统提示词缓存 */
    private final Map<String, String> systemPromptCache = new ConcurrentHashMap<>();

    public AgentLoop(EventStore eventStore, PluginRegistry pluginRegistry,
                     ConversationHistory conversationHistory, AdapterManager adapterManager,
                     AgentControlManager agentControl, SessionManager sessionManager,
                     com.lioncode.core.workspace.WorkspaceManager workspaceManager) {
        this.eventStore = eventStore;
        this.pluginRegistry = pluginRegistry;
        this.conversationHistory = conversationHistory;
        this.adapterManager = adapterManager;
        this.agentControl = agentControl;
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
    }

    /**
     * 处理用户消息（同步模式）
     * 
     * @param sessionId 会话ID
     * @param userMessage 用户输入
     * @param mode 工作模式
     * @param thinkingLevel 思考等级
     * @param model 模型名称
     * @return Agent最终响应
     */
    public String processMessage(String sessionId, String userMessage, AgentMode mode,
                                  ThinkingLevel thinkingLevel, String model) {
        log.info("=== Agent主循环开始 === 会话: {}, 模式: {}, 模型: {}", 
            sessionId, mode.getCode(), model);

        // 1. 记录用户消息事件
        eventStore.recordEvent(sessionId, LionEvent.EventType.USER_MESSAGE,
            Map.of("content", userMessage), "用户消息: " + truncate(userMessage, 100));

        // 2. 保存用户消息到对话历史
        conversationHistory.addMessage(ConversationMessage.user(sessionId, userMessage));

        // 2.5 新任务开始：清除之前的暂停/停止状态
        agentControl.reset(sessionId);

        // 3. 构建消息列表
        List<ChatMessage> messages = buildMessages(sessionId, mode);

        // 3.5 构建工具定义列表
        List<Map<String, Object>> toolDefinitions = buildToolDefinitions(mode);
        log.info("可用工具数量: {}", toolDefinitions.size());

        // 4. 工具调用循环（无轮次上限，直到模型给出最终答案）
        ModelAdapter adapter = adapterManager.getActiveAdapter();
        int round = 0;

        while (true) {
            round++;
            log.info("--- 工具调用轮次 {} ---", round);

            // 控制检查：暂停时阻塞等待，停止时中止任务
            try {
                agentControl.checkControl(sessionId);
            } catch (AgentControlManager.AgentStoppedException e) {
                String stopMsg = "⏹ 任务已手动停止（共执行 " + (round - 1) + " 轮工具调用）。";
                log.info(stopMsg + " 会话: {}", sessionId);
                eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                    Map.of("error", "手动停止"), "任务已手动停止");
                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, stopMsg));
                return stopMsg;
            }

            // 5. 调用模型
            eventStore.recordEvent(sessionId, LionEvent.EventType.MODEL_THINKING,
                Map.of("round", round), "模型思考中...");
            
            ModelResponse response;
            try {
                response = adapter.chat(messages, model, thinkingLevel, toolDefinitions);
            } catch (Exception e) {
                log.error("模型调用失败", e);
                eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                    Map.of("error", e.getMessage()), "模型调用失败: " + e.getMessage());
                return "模型调用失败: " + e.getMessage();
            }

            eventStore.recordEvent(sessionId, LionEvent.EventType.MODEL_RESPONSE,
                Map.of("content", truncate(response.content(), 200), 
                       "hasToolCalls", response.toolCalls() != null && !response.toolCalls().isEmpty()),
                "模型响应");

            // 6. 如果没有工具调用，检查文本中是否有XML/JSON格式的工具调用
            List<ChatMessage.ToolCall> toolCalls = response.toolCalls();
            String responseContent = response.content() != null ? response.content() : "";
            
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 尝试从文本中解析工具调用（支持XML和JSON格式）
                toolCalls = parseToolCallsFromText(responseContent);
                if (!toolCalls.isEmpty()) {
                    // 提取纯文本部分（去掉工具调用标签）
                    responseContent = removeToolCallBlocks(responseContent);
                    log.info("从文本中解析到 {} 个工具调用", toolCalls.size());
                }
            }

            // 6.5 强制单工具：一次只能执行一个工具调用（多余的丢弃并提示模型）
            boolean droppedExtraTools = false;
            if (toolCalls.size() > 1) {
                String firstTool = toolCalls.get(0).name();
                log.warn("模型一次返回 {} 个工具调用，仅执行第一个 ({}) - 会话: {}",
                    toolCalls.size(), firstTool, sessionId);
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                    Map.of("toolName", firstTool, "droppedCount", toolCalls.size() - 1),
                    "单工具限制：已忽略多余工具调用 " + (toolCalls.size() - 1) + " 个");
                toolCalls = List.of(toolCalls.get(0));
                droppedExtraTools = true;
            }

            // 7. 如果仍然没有工具调用，返回最终答案
            if (toolCalls.isEmpty()) {
                String finalAnswer = responseContent;
                
                // 保存助手消息
                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, finalAnswer));
                
                log.info("=== Agent主循环结束 === 共 {} 轮工具调用", round - 1);
                return finalAnswer;
            }

            // 8. 有工具调用：保存助手消息（含工具调用，过滤无效的）
            List<ConversationMessage.ToolCallRecord> toolCallRecords = toolCalls.stream()
                .filter(tc -> tc.name() != null && !tc.name().isBlank())
                .map(tc -> new ConversationMessage.ToolCallRecord(tc.id(), tc.name(), tc.arguments()))
                .toList();
            if (!toolCallRecords.isEmpty()) {
                conversationHistory.addMessage(
                    ConversationMessage.assistantWithToolCalls(sessionId, responseContent, toolCallRecords,
                        response.reasoningContent()));
            } else {
                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, responseContent,
                    response.reasoningContent()));
            }

            // 9. 执行工具调用（流式：识别到就执行；执行前检查暂停/停止）
            for (ChatMessage.ToolCall toolCall : toolCalls) {
                try {
                    agentControl.checkControl(sessionId);
                } catch (AgentControlManager.AgentStoppedException e) {
                    String stopMsg = "⏹ 任务已手动停止（共执行 " + round + " 轮工具调用）。";
                    log.info(stopMsg + " 会话: {}", sessionId);
                    eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                        Map.of("error", "手动停止"), "任务已手动停止");
                    conversationHistory.addMessage(ConversationMessage.assistant(sessionId, stopMsg));
                    return stopMsg;
                }
                if (toolCall.name() != null && !toolCall.name().isBlank()) {
                    executeTool(sessionId, toolCall, mode);
                }
            }

            // 9.5 单工具限制提示：告诉模型多余的调用被忽略了
            if (droppedExtraTools) {
                conversationHistory.addMessage(ConversationMessage.system(sessionId,
                    "【系统提示】一次只能调用一个工具。你上次一次返回了多个工具调用，"
                    + "多余的调用已被忽略，仅执行了第一个。请每次只输出一个工具调用，等待结果后再继续。"));
            }

            // 10. 更新消息列表，继续下一轮
            messages = buildMessages(sessionId, mode);
        }
    }

    /**
     * 流式处理用户消息
     * 返回Flux流，支持前端实时展示
     * 
     * 支持完整的工具调用循环：
     * 1. 流式收集模型输出（文本+工具调用增量）
     * 2. 流结束后，如果有工具调用则执行并继续下一轮
     * 3. 仅当模型不再调用工具时才发送完成信号
     */
    public Flux<AgentChunk> processMessageStream(String sessionId, String userMessage, 
                                                   AgentMode mode, ThinkingLevel thinkingLevel, 
                                                   String model) {
        return Flux.create(sink -> {
            try {
                // 记录用户消息
                eventStore.recordEvent(sessionId, LionEvent.EventType.USER_MESSAGE,
                    Map.of("content", userMessage), "用户消息");
                conversationHistory.addMessage(ConversationMessage.user(sessionId, userMessage));

                // 新任务开始：清除之前的暂停/停止状态
                agentControl.reset(sessionId);

                List<ChatMessage> messages = buildMessages(sessionId, mode);
                ModelAdapter adapter = adapterManager.getActiveAdapter();

                // 构建工具定义列表
                List<Map<String, Object>> toolDefinitions = buildToolDefinitions(mode);

                // 流式调用模型
                StringBuilder contentBuilder = new StringBuilder();
                StringBuilder reasoningBuilder = new StringBuilder();
                
                // 工具调用增量累积器：index -> {id, nameBuilder, argsBuilder}
                Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
                
                adapter.chatStream(messages, model, thinkingLevel, toolDefinitions)
                    .doOnNext(chunk -> {
                        // 实时发送文本增量
                        if (chunk.deltaContent() != null && !chunk.deltaContent().isEmpty()) {
                            contentBuilder.append(chunk.deltaContent());
                            sink.next(AgentChunk.text(chunk.deltaContent()));
                        }
                        // 累积思考内容（thinking模式回传）
                        if (chunk.reasoningContentDelta() != null && !chunk.reasoningContentDelta().isEmpty()) {
                            reasoningBuilder.append(chunk.reasoningContentDelta());
                        }
                        // 累积工具调用增量
                        if (chunk.toolCallDeltas() != null) {
                            for (ModelChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
                                ToolCallAccumulator acc = toolCallAccumulators.computeIfAbsent(
                                    delta.index(), k -> new ToolCallAccumulator());
                                if (delta.id() != null) acc.id = delta.id();
                                if (delta.nameDelta() != null) acc.nameBuilder.append(delta.nameDelta());
                                if (delta.argumentsDelta() != null) acc.argsBuilder.append(delta.argumentsDelta());
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            String fullContent = contentBuilder.toString();
                            
                            // 从累积器构建完整的工具调用列表
                            List<ChatMessage.ToolCall> toolCalls = buildToolCallsFromAccumulators(toolCallAccumulators);
                            
                            // 如果累积器没有工具调用，尝试从文本中解析工具调用（XML和JSON格式）
                            if (toolCalls.isEmpty()) {
                                toolCalls = parseToolCallsFromText(fullContent);
                                if (!toolCalls.isEmpty()) {
                                    fullContent = removeToolCallBlocks(fullContent);
                                    log.info("从流式文本中解析到 {} 个工具调用", toolCalls.size());
                                }
                            } else {
                                log.info("从流式增量中累积到 {} 个工具调用", toolCalls.size());
                            }
                            
                            if (toolCalls.isEmpty()) {
                                // 没有工具调用，流结束
                                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, fullContent,
                                    reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null));
                                sink.next(AgentChunk.done(fullContent));
                                sink.complete();
                            } else {
                                // 强制单工具：一次只能执行一个工具调用
                                boolean droppedExtraTools = false;
                                if (toolCalls.size() > 1) {
                                    log.warn("模型一次返回 {} 个工具调用，仅执行第一个 ({}) - 会话: {}",
                                        toolCalls.size(), toolCalls.get(0).name(), sessionId);
                                    toolCalls = List.of(toolCalls.get(0));
                                    droppedExtraTools = true;
                                }

                                // 有工具调用：保存助手消息，执行工具，然后继续下一轮
                                sink.next(AgentChunk.text("\n\n🔧 正在执行工具调用...\n\n"));
                                
                                String reasoning = reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null;
                                List<ConversationMessage.ToolCallRecord> toolCallRecords = toolCalls.stream()
                                    .filter(tc -> tc.name() != null && !tc.name().isBlank())
                                    .map(tc -> new ConversationMessage.ToolCallRecord(tc.id(), tc.name(), tc.arguments()))
                                    .toList();
                                if (!toolCallRecords.isEmpty()) {
                                    conversationHistory.addMessage(
                                        ConversationMessage.assistantWithToolCalls(sessionId, fullContent,
                                            toolCallRecords, reasoning));
                                } else {
                                    conversationHistory.addMessage(ConversationMessage.assistant(sessionId, fullContent,
                                        reasoning));
                                }

                                // 执行所有工具调用
                                for (ChatMessage.ToolCall toolCall : toolCalls) {
                                    if (toolCall.name() != null && !toolCall.name().isBlank()) {
                                        executeTool(sessionId, toolCall, mode);
                                        // 发送工具执行结果通知
                                        sink.next(AgentChunk.toolCall(toolCall.name(), "执行完成"));
                                    }
                                }

                                // 单工具限制提示
                                if (droppedExtraTools) {
                                    conversationHistory.addMessage(ConversationMessage.system(sessionId,
                                        "【系统提示】一次只能调用一个工具。你上次一次返回了多个工具调用，"
                                        + "多余的调用已被忽略，仅执行了第一个。请每次只输出一个工具调用，等待结果后再继续。"));
                                }

                                // 继续下一轮（递归调用），使用更新后的消息列表
                                List<ChatMessage> nextMessages = buildMessages(sessionId, mode);
                                processStreamRound(sink, sessionId, nextMessages, model, thinkingLevel, 
                                    adapter, toolDefinitions, mode, 1);
                            }
                        } catch (Exception e) {
                            log.error("流式处理工具调用失败", e);
                            sink.error(e);
                        }
                    })
                    .doOnError(e -> {
                        log.error("流式调用失败", e);
                        sink.error(e);
                    })
                    .subscribe();
                    
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    /**
     * 流式处理的后续轮次（工具调用后继续对话）
     * 
     * @param sink Flux发射器
     * @param sessionId 会话ID
     * @param messages 当前消息列表
     * @param model 模型名称
     * @param thinkingLevel 思考等级
     * @param adapter 模型适配器
     * @param toolDefinitions 工具定义列表
     * @param mode 工作模式
     * @param currentRound 当前轮次（仅用于日志，无上限）
     */
    private void processStreamRound(reactor.core.publisher.FluxSink<AgentChunk> sink,
                                      String sessionId, List<ChatMessage> messages, String model,
                                      ThinkingLevel thinkingLevel, ModelAdapter adapter,
                                      List<Map<String, Object>> toolDefinitions, AgentMode mode,
                                      int currentRound) {
        // 控制检查：暂停时阻塞等待，停止时中止流
        try {
            agentControl.checkControl(sessionId);
        } catch (AgentControlManager.AgentStoppedException e) {
            log.info("流式任务已手动停止: {} (轮次 {})", sessionId, currentRound);
            sink.next(AgentChunk.error("⏹ 任务已手动停止"));
            sink.complete();
            return;
        }

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();

        adapter.chatStream(messages, model, thinkingLevel, toolDefinitions)
            .doOnNext(chunk -> {
                if (chunk.deltaContent() != null && !chunk.deltaContent().isEmpty()) {
                    contentBuilder.append(chunk.deltaContent());
                    sink.next(AgentChunk.text(chunk.deltaContent()));
                }
                // 累积思考内容（thinking模式回传）
                if (chunk.reasoningContentDelta() != null && !chunk.reasoningContentDelta().isEmpty()) {
                    reasoningBuilder.append(chunk.reasoningContentDelta());
                }
                if (chunk.toolCallDeltas() != null) {
                    for (ModelChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
                        ToolCallAccumulator acc = toolCallAccumulators.computeIfAbsent(
                            delta.index(), k -> new ToolCallAccumulator());
                        if (delta.id() != null) acc.id = delta.id();
                        if (delta.nameDelta() != null) acc.nameBuilder.append(delta.nameDelta());
                        if (delta.argumentsDelta() != null) acc.argsBuilder.append(delta.argumentsDelta());
                    }
                }
            })
            .doOnComplete(() -> {
                try {
                    String fullContent = contentBuilder.toString();
                    
                    List<ChatMessage.ToolCall> toolCalls = buildToolCallsFromAccumulators(toolCallAccumulators);
                    
                    if (toolCalls.isEmpty()) {
                        toolCalls = parseToolCallsFromText(fullContent);
                        if (!toolCalls.isEmpty()) {
                            fullContent = removeToolCallBlocks(fullContent);
                            log.info("从流式文本中解析到 {} 个工具调用 (轮次 {})", toolCalls.size(), currentRound + 1);
                        }
                    } else {
                        log.info("从流式增量中累积到 {} 个工具调用 (轮次 {})", toolCalls.size(), currentRound + 1);
                    }
                    
                    if (toolCalls.isEmpty()) {
                        // 没有工具调用，流结束
                        conversationHistory.addMessage(ConversationMessage.assistant(sessionId, fullContent,
                            reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null));
                        log.info("=== 流式Agent循环结束 === 共 {} 轮工具调用", currentRound);
                        sink.next(AgentChunk.done(fullContent));
                        sink.complete();
                    } else {
                        // 强制单工具：一次只能执行一个工具调用
                        boolean droppedExtraTools = false;
                        if (toolCalls.size() > 1) {
                            log.warn("模型一次返回 {} 个工具调用，仅执行第一个 ({}) - 会话: {} (轮次 {})",
                                toolCalls.size(), toolCalls.get(0).name(), sessionId, currentRound + 1);
                            toolCalls = List.of(toolCalls.get(0));
                            droppedExtraTools = true;
                        }

                        // 继续执行工具
                        sink.next(AgentChunk.text("\n\n🔧 正在执行工具调用...\n\n"));
                        
                        String reasoning = reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null;
                        List<ConversationMessage.ToolCallRecord> toolCallRecords = toolCalls.stream()
                            .filter(tc -> tc.name() != null && !tc.name().isBlank())
                            .map(tc -> new ConversationMessage.ToolCallRecord(tc.id(), tc.name(), tc.arguments()))
                            .toList();
                        if (!toolCallRecords.isEmpty()) {
                            conversationHistory.addMessage(
                                ConversationMessage.assistantWithToolCalls(sessionId, fullContent,
                                    toolCallRecords, reasoning));
                        } else {
                            conversationHistory.addMessage(ConversationMessage.assistant(sessionId, fullContent,
                                reasoning));
                        }

                        for (ChatMessage.ToolCall toolCall : toolCalls) {
                            if (toolCall.name() != null && !toolCall.name().isBlank()) {
                                executeTool(sessionId, toolCall, mode);
                                sink.next(AgentChunk.toolCall(toolCall.name(), "执行完成"));
                            }
                        }

                        // 单工具限制提示
                        if (droppedExtraTools) {
                            conversationHistory.addMessage(ConversationMessage.system(sessionId,
                                "【系统提示】一次只能调用一个工具。你上次一次返回了多个工具调用，"
                                + "多余的调用已被忽略，仅执行了第一个。请每次只输出一个工具调用，等待结果后再继续。"));
                        }

                        List<ChatMessage> nextMessages = buildMessages(sessionId, mode);
                        processStreamRound(sink, sessionId, nextMessages, model, thinkingLevel, 
                            adapter, toolDefinitions, mode, currentRound + 1);
                    }
                } catch (Exception e) {
                    log.error("流式处理工具调用失败 (轮次 {})", currentRound, e);
                    sink.error(e);
                }
            })
            .doOnError(e -> {
                log.error("流式调用失败 (轮次 {})", currentRound, e);
                sink.error(e);
            })
            .subscribe();
    }

    /**
     * 工具调用增量累积器
     */
    private static class ToolCallAccumulator {
        String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        final StringBuilder nameBuilder = new StringBuilder();
        final StringBuilder argsBuilder = new StringBuilder();
    }

    /**
     * 从累积器构建完整的工具调用列表
     */
    private List<ChatMessage.ToolCall> buildToolCallsFromAccumulators(Map<Integer, ToolCallAccumulator> accumulators) {
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        
        // 按index排序
        List<Integer> sortedIndices = new ArrayList<>(accumulators.keySet());
        Collections.sort(sortedIndices);
        
        for (Integer index : sortedIndices) {
            ToolCallAccumulator acc = accumulators.get(index);
            String name = acc.nameBuilder.toString().trim();
            String argsStr = acc.argsBuilder.toString().trim();
            
            if (name.isEmpty()) {
                log.warn("工具调用累积器中name为空，跳过 index={}", index);
                continue;
            }
            
            Map<String, Object> arguments;
            try {
                if (argsStr.isEmpty()) {
                    arguments = Map.of();
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(argsStr, Map.class);
                    arguments = parsed;
                }
            } catch (Exception e) {
                log.warn("解析工具调用参数失败: {}, 原始: {}", name, argsStr);
                arguments = Map.of();
            }
            
            toolCalls.add(new ChatMessage.ToolCall(acc.id, name, arguments));
        }
        
        return toolCalls;
    }

    /**
     * 执行单个工具调用
     */
    private void executeTool(String sessionId, ChatMessage.ToolCall toolCall, AgentMode mode) {
        String toolName = toolCall.name();
        
        // 记录工具调用开始
        eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_START,
            Map.of("toolName", toolName, "arguments", toolCall.arguments()),
            "工具调用开始: " + toolName);

        // 查找工具插件
        Optional<ToolPlugin> toolOpt = pluginRegistry.getToolPlugins().stream()
            .filter(t -> t.getName().equals(toolName) || t.getId().equals(toolName))
            .findFirst();

        if (toolOpt.isEmpty()) {
            String error = "未找到工具: " + toolName;
            log.error(error);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", error), error);
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, "错误: " + error));
            return;
        }

        ToolPlugin tool = toolOpt.get();

        // 检查模式权限
        if (!tool.isAvailableInMode(mode)) {
            String error = "工具 " + toolName + " 在 " + mode.getCode() + " 模式下不可用";
            log.warn(error);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", error), error);
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, "错误: " + error));
            return;
        }

        // 执行工具（设置会话工作区上下文：相对路径基于绑定的工作区解析）
        sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .ifPresent(ws -> com.lioncode.core.workspace.WorkspaceContext.set(ws.path()));
        try {
            ToolResult result = tool.execute(toolCall.arguments());
            
            if (result.success()) {
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_COMPLETE,
                    Map.of("toolName", toolName, "result", truncate(result.content(), 500)),
                    "工具调用成功: " + toolName);
                conversationHistory.addMessage(
                    ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, result.content()));
            } else {
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                    Map.of("toolName", toolName, "error", result.error()),
                    "工具调用失败: " + toolName);
                conversationHistory.addMessage(
                    ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, 
                        "工具执行错误: " + result.error()));
            }
        } catch (Exception e) {
            log.error("工具执行异常: {}", toolName, e);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", e.getMessage()),
                "工具执行异常: " + e.getMessage());
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName,
                    "工具执行异常: " + e.getMessage()));
        } finally {
            com.lioncode.core.workspace.WorkspaceContext.clear();
        }
    }

    /**
     * 构建模型消息列表
     */
    private List<ChatMessage> buildMessages(String sessionId, AgentMode mode) {
        List<ChatMessage> messages = new ArrayList<>();

        // 会话绑定的工作区路径
        String workspacePath = sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .map(ws -> ws.path())
            .orElse(null);

        // 系统提示词
        String systemPrompt = buildSystemPrompt(mode, workspacePath);
        messages.add(ChatMessage.system(systemPrompt));

        // 对话历史
        List<ConversationMessage> history = conversationHistory.getHistory(sessionId);
        for (ConversationMessage msg : history) {
            switch (msg.role()) {
                case "user" -> messages.add(ChatMessage.user(msg.content()));
                case "assistant" -> {
                    if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                        List<ChatMessage.ToolCall> tcList = msg.toolCalls().stream()
                            .filter(tc -> tc.name() != null && !tc.name().isBlank()) // 过滤无效工具调用
                            .map(tc -> new ChatMessage.ToolCall(tc.id(), tc.name(), tc.arguments()))
                            .toList();
                        // 只有存在有效工具调用时才添加tool_calls
                        if (!tcList.isEmpty()) {
                            messages.add(ChatMessage.assistantWithToolCalls(msg.content(), tcList,
                                msg.reasoningContent()));
                        } else {
                            messages.add(ChatMessage.assistant(msg.content(), msg.reasoningContent()));
                        }
                    } else {
                        messages.add(ChatMessage.assistant(msg.content(), msg.reasoningContent()));
                    }
                }
                case "system" -> messages.add(ChatMessage.system(msg.content()));
                case "tool" -> messages.add(ChatMessage.toolResult(msg.toolCallId(), msg.content()));
            }
        }

        return messages;
    }

    /**
     * 构建系统提示词
     * 
     * 核心策略：给出明确的工具调用指令和具体示例，
     * 让模型知道必须调用工具而非只给文字建议。
     */
    private String buildSystemPrompt(AgentMode mode, String workspacePath) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是Lion-Code Agent，一个强大的AI编程助手。你的核心能力是通过调用工具来实际操作文件、执行命令、完成任务。\n\n");

        // 当前工作区：所有文件操作和命令执行都在此工作区内进行
        prompt.append("## 当前工作区\n\n");
        prompt.append("工作区路径: ").append(workspacePath != null ? workspacePath : "(未设置)").append("\n");
        prompt.append("所有文件操作和命令执行都必须在这个工作区内进行。");
        prompt.append("文件工具的参数path支持相对路径（相对工作区根目录）或绝对路径。\n\n");

        // 根据模式添加提示
        switch (mode) {
            case PTC -> prompt.append("你处于PTC模式：请先完整规划所有工具调用步骤，然后按顺序执行。\n");
            case CREATIVE -> prompt.append("你处于创造模式：你可以编写、修改、安装、卸载插件来扩展自身能力。\n");
            case STANDARD -> prompt.append("你处于标准模式：所有工具均可使用。\n");
            case MINIMAL -> prompt.append("你处于极简模式：仅可使用文件和Shell工具。\n");
        }

        prompt.append("\n## ⚠️ 核心规则：必须调用工具\n\n");
        prompt.append("当用户请求涉及以下操作时，你**必须**调用相应工具来执行，**绝对不能**只给出文字描述或建议：\n");
        prompt.append("- 读取/写入/修改/删除文件 → 调用 read_file / write_file / modify_file / delete_file\n");
        prompt.append("- 执行命令（编译、运行、安装等）→ 调用 execute_command\n");
        prompt.append("- 查看目录/搜索文件 → 调用 list_files / search_files / glob_files\n");
        prompt.append("- Git操作 → 调用 git_status / git_commit / git_diff 等\n");
        prompt.append("- 查看系统信息 → 调用 system_info / env_var / working_dir\n\n");

        // 添加可用工具描述
        List<ToolPlugin> tools = pluginRegistry.getToolsByMode(mode);
        if (!tools.isEmpty()) {
            prompt.append("### 可用工具列表\n\n");
            for (ToolPlugin tool : tools) {
                prompt.append("- **").append(tool.getName()).append("**: ").append(tool.getDescription()).append("\n");
            }
            prompt.append("\n### 工具调用格式\n\n");
            prompt.append("你**必须**使用以下两种格式之一来调用工具：\n\n");
            
            prompt.append("#### 格式一：XML格式（推荐）\n\n");
            prompt.append("```xml\n");
            prompt.append("<tool_call>\n");
            prompt.append("  <name>工具名称</name>\n");
            prompt.append("  <arguments>{\"参数名\": \"参数值\"}</arguments>\n");
            prompt.append("</tool_call>\n");
            prompt.append("```\n\n");

            prompt.append("#### 格式二：JSON格式\n\n");
            prompt.append("```json\n");
            prompt.append("<tool_call>\n");
            prompt.append("{\"name\": \"工具名称\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
            prompt.append("</tool_call>\n");
            prompt.append("```\n\n");

            // 给出具体示例
            prompt.append("### 调用示例\n\n");
            
            prompt.append("**示例1：读取文件（XML格式）**\n");
            prompt.append("用户：帮我看看 src/main.java 的内容\n");
            prompt.append("你的回复：好的，我来读取这个文件。\n");
            prompt.append("```xml\n");
            prompt.append("<tool_call>\n");
            prompt.append("  <name>read_file</name>\n");
            prompt.append("  <arguments>{\"path\": \"src/main.java\"}</arguments>\n");
            prompt.append("</tool_call>\n");
            prompt.append("```\n\n");

            prompt.append("**示例2：执行命令（JSON格式）**\n");
            prompt.append("用户：帮我编译这个项目\n");
            prompt.append("你的回复：好的，我来执行编译。\n");
            prompt.append("```json\n");
            prompt.append("<tool_call>\n");
            prompt.append("{\"name\": \"execute_command\", \"arguments\": {\"command\": \"mvn compile\"}}\n");
            prompt.append("</tool_call>\n");
            prompt.append("```\n\n");

            prompt.append("**示例3：写入文件（XML格式）**\n");
            prompt.append("用户：创建一个 hello.py 文件\n");
            prompt.append("你的回复：好的，我来创建文件。\n");
            prompt.append("```xml\n");
            prompt.append("<tool_call>\n");
            prompt.append("  <name>write_file</name>\n");
            prompt.append("  <arguments>{\"path\": \"hello.py\", \"content\": \"print('Hello, World!')\"}</arguments>\n");
            prompt.append("</tool_call>\n");
            prompt.append("```\n\n");

            prompt.append("**重要**：\n");
            prompt.append("1. 【强制规则】一次只能调用一个工具，绝对不要在一次回复中输出多个工具调用；必须等待工具结果返回后再决定下一步\n");
            prompt.append("2. 工具调用必须放在代码块中（```xml 或 ```json）\n");
            prompt.append("3. arguments必须是合法的JSON字符串\n");
            prompt.append("4. 不要在工具调用前后添加多余文字，直接给出调用即可\n");
        }

        return prompt.toString();
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 构建工具定义列表（OpenAI function calling格式）
     * 
     * 从插件注册表获取当前模式下可用的工具，
     * 转换为模型API所需的工具定义格式。
     */
    private List<Map<String, Object>> buildToolDefinitions(AgentMode mode) {
        List<ToolPlugin> tools = pluginRegistry.getToolsByMode(mode);
        if (tools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ToolPlugin tool : tools) {
            // 跳过name或description为空的工具，防止模型API返回400错误
            if (tool.getName() == null || tool.getName().isBlank()
                    || tool.getDescription() == null || tool.getDescription().isBlank()) {
                log.warn("跳过无效工具定义（name/description为空）: id={}", tool.getId());
                continue;
            }
            Map<String, Object> funcDef = tool.getFunctionDefinition();
            if (funcDef.get("name") == null || funcDef.get("description") == null) {
                log.warn("跳过无效工具定义（function定义不完整）: id={}", tool.getId());
                continue;
            }
            definitions.add(funcDef);
        }
        return definitions;
    }

    /**
     * 从文本中解析工具调用（支持XML和JSON格式）
     * 
     * 先尝试解析XML格式，如果没有结果再尝试JSON格式。
     * 
     * @param text 包含工具调用的文本
     * @return 解析到的工具调用列表
     */
    private List<ChatMessage.ToolCall> parseToolCallsFromText(String text) {
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return toolCalls;
        }

        // 1. 先尝试解析XML格式
        toolCalls = parseXmlToolCalls(text);
        if (!toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 2. 再尝试解析JSON格式
        toolCalls = parseJsonToolCalls(text);
        return toolCalls;
    }

    /**
     * 从文本中移除工具调用块（支持XML和JSON格式）
     * 
     * @param text 原始文本
     * @return 移除工具调用块后的纯文本
     */
    private String removeToolCallBlocks(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 移除XML格式的工具调用
        String result = text.replaceAll("(?s)<tool_call>.*?</tool_call>", "").trim();
        // 移除JSON格式的工具调用（在<tool_call></tool_call>标签内）
        result = result.replaceAll("(?s)<tool_call>.*?</tool_call>", "").trim();
        // 移除空的代码块
        result = result.replaceAll("```(xml|json)\\s*```", "").trim();
        return result;
    }

    /**
     * 从文本中解析XML格式的工具调用
     * 
     * 支持格式：
     * <tool_call>
     *   <name>tool_name</name>
     *   <arguments>{"key": "value"}</arguments>
     * </tool_call>
     * 
     * 或简化格式：
     * <tool_call>tool_name({"key": "value"})</tool_call>
     */
    private List<ChatMessage.ToolCall> parseXmlToolCalls(String text) {
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return toolCalls;
        }

        // 匹配 <tool_call>...</tool_call> 块
        Pattern blockPattern = Pattern.compile("(?s)<tool_call>(.*?)</tool_call>");
        Matcher blockMatcher = blockPattern.matcher(text);

        while (blockMatcher.find()) {
            String block = blockMatcher.group(1).trim();
            
            // 尝试解析 <name>...</name> 和 <arguments>...</arguments>
            Pattern namePattern = Pattern.compile("<name>(.*?)</name>");
            Pattern argsPattern = Pattern.compile("(?s)<arguments>(.*?)</arguments>");
            
            Matcher nameMatcher = namePattern.matcher(block);
            Matcher argsMatcher = argsPattern.matcher(block);
            
            if (nameMatcher.find()) {
                String name = nameMatcher.group(1).trim();
                String argsStr = argsMatcher.find() ? argsMatcher.group(1).trim() : "{}";
                
                Map<String, Object> arguments;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(argsStr, Map.class);
                    arguments = parsed;
                } catch (Exception e) {
                    log.warn("解析工具调用参数失败: {}, 原始: {}", name, argsStr);
                    arguments = Map.of();
                }
                
                String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                toolCalls.add(new ChatMessage.ToolCall(id, name, arguments));
            }
        }

        return toolCalls;
    }

    /**
     * 从文本中解析JSON格式的工具调用
     * 
     * 支持格式：
     * 1. 标签格式：
     *    <tool_call>
     *    {"name": "tool_name", "arguments": {"key": "value"}}
     *    </tool_call>
     * 
     * 2. 代码块格式：
     *    ```json
     *    <tool_call>
     *    {"name": "tool_name", "arguments": {"key": "value"}}
     *    </tool_call>
     *    ```
     * 
     * 3. 直接JSON格式（在代码块中）：
     *    ```json
     *    {"name": "tool_name", "arguments": {"key": "value"}}
     *    ```
     * 
     * 4. 数组格式：
     *    ```json
     *    [{"name": "tool_name", "arguments": {"key": "value"}}]
     *    ```
     * 
     * 5. 裸JSON格式（不在代码块中）：
     *    {"name": "tool_name", "arguments": {"key": "value"}}
     * 
     * 6. 嵌套function格式：
     *    {"function": {"name": "tool_name", "arguments": {"key": "value"}}}
     *    {"type": "function", "function": {"name": "tool_name", "arguments": "{\"key\": \"value\"}"}}
     */
    private List<ChatMessage.ToolCall> parseJsonToolCalls(String text) {
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return toolCalls;
        }

        // 1. 尝试匹配<tool_call></tool_call>标签格式
        Pattern tagPattern = Pattern.compile("(?s)<tool_call>(.*?)</tool_call>");
        Matcher tagMatcher = tagPattern.matcher(text);
        while (tagMatcher.find()) {
            String jsonContent = tagMatcher.group(1).trim();
            ChatMessage.ToolCall toolCall = parseSingleJsonToolCall(jsonContent);
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }
        if (!toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 2. 尝试匹配```json ... ```代码块中的<tool_call></tool_call>
        Pattern jsonBlockWithTagsPattern = Pattern.compile("```json\\s*(?s)(.*?)```");
        Matcher jsonBlockWithTagsMatcher = jsonBlockWithTagsPattern.matcher(text);
        while (jsonBlockWithTagsMatcher.find()) {
            String blockContent = jsonBlockWithTagsMatcher.group(1).trim();
            // 检查是否包含<tool_call>标签
            if (blockContent.contains("<tool_call>")) {
                Pattern innerTagPattern = Pattern.compile("(?s)<tool_call>(.*?)</tool_call>");
                Matcher innerTagMatcher = innerTagPattern.matcher(blockContent);
                while (innerTagMatcher.find()) {
                    String jsonContent = innerTagMatcher.group(1).trim();
                    ChatMessage.ToolCall toolCall = parseSingleJsonToolCall(jsonContent);
                    if (toolCall != null) {
                        toolCalls.add(toolCall);
                    }
                }
            }
        }
        if (!toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 3. 尝试匹配```json ... ```代码块中的直接JSON对象或数组
        Pattern jsonBlockPattern = Pattern.compile("```json\\s*(?s)(.*?)```");
        Matcher jsonBlockMatcher = jsonBlockPattern.matcher(text);
        while (jsonBlockMatcher.find()) {
            String jsonContent = jsonBlockMatcher.group(1).trim();
            // 跳过包含<tool_call>标签的（已在上面处理）
            if (jsonContent.contains("<tool_call>")) {
                continue;
            }
            
            List<ChatMessage.ToolCall> parsed = parseJsonContentToToolCalls(jsonContent);
            toolCalls.addAll(parsed);
        }
        if (!toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 4. 尝试从裸JSON中解析（不在代码块中）
        // 匹配独立的JSON对象：以{开头，以}结尾的完整JSON
        Pattern bareJsonPattern = Pattern.compile("(?s)(\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\})");
        Matcher bareJsonMatcher = bareJsonPattern.matcher(text);
        while (bareJsonMatcher.find()) {
            String jsonContent = bareJsonMatcher.group(1).trim();
            // 跳过<tool_call>标签内的（已处理）
            // 检查这个JSON是否在<tool_call>标签内
            int start = bareJsonMatcher.start();
            String before = text.substring(0, start);
            if (before.contains("<tool_call>") && !before.contains("</tool_call>")) {
                continue;
            }
            
            ChatMessage.ToolCall toolCall = parseSingleJsonToolCall(jsonContent);
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }

        return toolCalls;
    }

    /**
     * 解析JSON内容为工具调用列表（支持单对象和数组）
     */
    private List<ChatMessage.ToolCall> parseJsonContentToToolCalls(String jsonContent) {
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        if (jsonContent == null || jsonContent.isBlank()) {
            return toolCalls;
        }
        
        jsonContent = jsonContent.trim();
        
        // 尝试解析为数组
        if (jsonContent.startsWith("[")) {
            try {
                List<Map<String, Object>> toolCallList = objectMapper.readValue(
                    jsonContent, new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> toolCallMap : toolCallList) {
                    ChatMessage.ToolCall toolCall = parseToolCallFromMap(toolCallMap);
                    if (toolCall != null) {
                        toolCalls.add(toolCall);
                    }
                }
            } catch (Exception e) {
                log.debug("JSON数组解析失败: {}", e.getMessage());
            }
            return toolCalls;
        }
        
        // 尝试解析为单个对象
        if (jsonContent.startsWith("{")) {
            ChatMessage.ToolCall toolCall = parseSingleJsonToolCall(jsonContent);
            if (toolCall != null) {
                toolCalls.add(toolCall);
            }
        }
        
        return toolCalls;
    }

    /**
     * 从JSON字符串解析单个工具调用
     * 
     * @param jsonContent JSON字符串内容
     * @return 解析到的工具调用，失败返回null
     */
    private ChatMessage.ToolCall parseSingleJsonToolCall(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return null;
        }
        
        try {
            Map<String, Object> toolCallMap = objectMapper.readValue(jsonContent, 
                new TypeReference<Map<String, Object>>() {});
            return parseToolCallFromMap(toolCallMap);
        } catch (Exception e) {
            log.debug("JSON工具调用解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从Map解析工具调用
     * 
     * 支持多种格式：
     * - {"name": "tool_name", "arguments": {...}}
     * - {"tool": "tool_name", "arguments": {...}}
     * - {"function": "tool_name", "arguments": {...}}
     * - {"function": {"name": "tool_name", "arguments": {...}}}  (嵌套格式)
     * - {"type": "function", "function": {"name": "tool_name", "arguments": "{...}"}}  (OpenAI格式)
     * 
     * @param toolCallMap 包含name和arguments的Map
     * @return 解析到的工具调用，失败返回null
     */
    @SuppressWarnings("unchecked")
    private ChatMessage.ToolCall parseToolCallFromMap(Map<String, Object> toolCallMap) {
        if (toolCallMap == null) {
            return null;
        }
        
        String name = null;
        Map<String, Object> arguments = new HashMap<>();
        
        // 1. 直接name字段
        Object nameObj = toolCallMap.get("name");
        if (nameObj instanceof String) {
            name = (String) nameObj;
        }
        
        // 2. 尝试tool字段
        if (name == null || name.isBlank()) {
            nameObj = toolCallMap.get("tool");
            if (nameObj instanceof String) {
                name = (String) nameObj;
            }
        }
        
        // 3. 尝试function字段（可能是字符串或嵌套对象）
        if (name == null || name.isBlank()) {
            Object funcObj = toolCallMap.get("function");
            if (funcObj instanceof String) {
                name = (String) funcObj;
            } else if (funcObj instanceof Map) {
                // 嵌套格式: {"function": {"name": "tool_name", "arguments": {...}}}
                Map<String, Object> funcMap = (Map<String, Object>) funcObj;
                Object nestedName = funcMap.get("name");
                if (nestedName instanceof String) {
                    name = (String) nestedName;
                }
                // 嵌套的arguments
                Object nestedArgs = funcMap.get("arguments");
                if (nestedArgs instanceof Map) {
                    arguments = (Map<String, Object>) nestedArgs;
                } else if (nestedArgs instanceof String) {
                    try {
                        arguments = objectMapper.readValue((String) nestedArgs, 
                            new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.debug("解析嵌套arguments字符串失败: {}", e.getMessage());
                    }
                }
            }
        }
        
        if (name == null || name.isBlank()) {
            log.debug("JSON工具调用缺少name字段: {}", toolCallMap);
            return null;
        }
        
        // 解析顶层arguments（如果嵌套格式没解析到）
        if (arguments.isEmpty()) {
            Object argsObj = toolCallMap.get("arguments");
            if (argsObj instanceof Map) {
                arguments = (Map<String, Object>) argsObj;
            } else if (argsObj instanceof String) {
                // arguments可能是JSON字符串
                try {
                    arguments = objectMapper.readValue((String) argsObj, 
                        new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.debug("解析arguments字符串失败: {}", e.getMessage());
                }
            }
        }
        
        String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return new ChatMessage.ToolCall(id, name.trim(), arguments);
    }

    /**
     * Agent流式输出块
     */
    public record AgentChunk(
        ChunkType type,
        String content,
        String toolName,
        boolean finished
    ) {
        public enum ChunkType {
            TEXT, TOOL_CALL, DONE, ERROR
        }

        public static AgentChunk text(String content) {
            return new AgentChunk(ChunkType.TEXT, content, null, false);
        }

        public static AgentChunk toolCall(String toolName, String message) {
            return new AgentChunk(ChunkType.TOOL_CALL, message, toolName, false);
        }

        public static AgentChunk done(String fullContent) {
            return new AgentChunk(ChunkType.DONE, fullContent, null, true);
        }

        public static AgentChunk error(String error) {
            return new AgentChunk(ChunkType.ERROR, error, null, true);
        }
    }
}
