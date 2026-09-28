package com.lioncode.core.agent;

import com.lioncode.core.event.EventStore;
import com.lioncode.core.event.LionEvent;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.skill.SkillPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.ConversationMessage;
import com.lioncode.core.session.SessionManager;
import com.lioncode.approval.ApprovalPolicy;
import com.lioncode.model.adapter.*;
import com.lioncode.core.sound.SoundNotifier;
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
    private final ApprovalPolicy approvalPolicy;
    private final com.lioncode.core.sound.SoundNotifier soundNotifier;
    private final com.lioncode.model.config.AppConfigStore configStore;

    /** 每个会话的系统提示词缓存 */
    private final Map<String, String> systemPromptCache = new ConcurrentHashMap<>();

    public AgentLoop(EventStore eventStore, PluginRegistry pluginRegistry,
                     ConversationHistory conversationHistory, AdapterManager adapterManager,
                     AgentControlManager agentControl, SessionManager sessionManager,
                     com.lioncode.core.workspace.WorkspaceManager workspaceManager,
                     ApprovalPolicy approvalPolicy,
                     com.lioncode.core.sound.SoundNotifier soundNotifier,
                     com.lioncode.model.config.AppConfigStore configStore) {
        this.eventStore = eventStore;
        this.pluginRegistry = pluginRegistry;
        this.conversationHistory = conversationHistory;
        this.adapterManager = adapterManager;
        this.agentControl = agentControl;
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
        this.approvalPolicy = approvalPolicy;
        this.soundNotifier = soundNotifier;
        this.configStore = configStore;
    }

    /**
     * 残缺工具调用 / 空响应的最大纠正次数。
     * 有上限才不会因为模型反复吐残缺调用而把会话卡死；超了就按正常收尾处理。
     */
    private static final int MAX_CALL_REPAIR = 2;

    /**
     * 纠正提示词：针对「模型想做工具调用但调用残缺」和「整轮什么都没输出」两种失手。
     *
     * 这两种情况在旧版里都表现为「没有工具调用 + 正文为空」→ 直接当成最终答案返回，
     * 用户看到一句空白回答，任务被静默结束（实测 MiMo 在提示词里看到文本格式示例时
     * 就会返回 name=null、arguments="{}"、finish_reason=stop 的残缺调用）。
     */
    private String repairHint(boolean malformed) {
        if (malformed) {
            return "【系统提示】你上一次的工具调用是**残缺的**（缺少工具名，或 arguments 不是合法的 JSON），"
                 + "因此没有被执行。请重新输出**一次完整**的工具调用：只调用一个工具，"
                 + "工具名必须来自可用工具列表，arguments 必须是合法的 JSON 对象。";
        }
        return "【系统提示】你上一次没有输出任何内容（正文为空，也没有工具调用）。"
             + "请直接给出结论，或者调用合适的工具继续推进任务。";
    }

    /**
     * 预热计划：与真实请求**逐字节一致**的前缀（系统提示词 + 工具定义）。
     *
     * @param messages        消息体（系统提示词 + 一句占位用户消息）
     * @param toolDefinitions 与真实请求同一份工具定义（顺序、序列化都必须一致，
     *                        否则 llama-server 的前缀缓存命不中）
     */
    public record WarmupPlan(List<ChatMessage> messages,
                             List<Map<String, Object>> toolDefinitions) {}

    /**
     * 构造预热请求，供 {@link com.lioncode.model.runtime.PrewarmService} 用。
     *
     * 关键点：系统提示词必须和真实请求一模一样，所以这里走的是**同一个
     * buildSystemPrompt 和同一个 buildToolDefinitions**，不另写一份。
     * 用户消息传空串 → 技能块不命中 → 与绝大多数真实请求的提示词一致。
     */
    public WarmupPlan buildWarmupPlan(AgentMode mode, String workspacePath) {
        boolean nativeTools = useNativeTools();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(mode, workspacePath, "", nativeTools)));
        messages.add(ChatMessage.user("预热"));
        List<Map<String, Object>> tools = nativeTools ? buildToolDefinitions(mode) : List.of();
        return new WarmupPlan(messages, tools);
    }

    /**
     * 本轮是否走**原生 function calling**（把 tools 定义随请求下发）。     *
     * 三种取值（设置里可切，默认 auto）：
     *   auto   → 默认都走原生：云端 OpenAI 兼容 API 和随盒子的 llama-server 都支持。
     *            llama-server 会按模型的 chat 模板把原生语法解析成标准 tool_calls；
     *            实测不下发 tools 时模型会开始**编造工具**，所以本地也必须下发。
     *   native → 强制下发 tools
     *   text   → 强制不下发 tools，只靠系统提示词里的文本格式（给不认 tools 的端点兜底）
     *
     * 无论哪种方式，**两种格式的解析器都在**：模型用哪种回就认哪种。
     */
    private boolean useNativeTools() {
        String mode = configStore == null
            ? com.lioncode.model.config.AppConfigStore.TOOLCALL_AUTO
            : configStore.toolCallMode();
        com.lioncode.model.adapter.ModelAdapter adapter = adapterManager.getActiveAdapter();
        // 端点刚刚拒过 tools：这一轮别再用原生通道了，否则会「工具被拒 + 提示词不教文本格式」双输
        boolean rejected = adapter != null && adapter.toolDefinitionsRejected();
        if (com.lioncode.model.config.AppConfigStore.TOOLCALL_TEXT.equals(mode)) {
            return false;
        }
        if (com.lioncode.model.config.AppConfigStore.TOOLCALL_NATIVE.equals(mode)) {
            return !rejected;
        }
        // ---- AUTO ----
        // 本地模型必须走文本 <tool_call> 约定，两个理由都是硬事实：
        //   1) 随包交付的 llama-server **没开 --jinja**，请求里的 tools 会被服务端直接丢掉，
        //      模型根本看不到工具定义 —— 下发 tools 纯粹白烧 token（tools 的 JSON 有 4-5K token，
        //      每次请求都要预填充，本机实测首个请求前缀被顶到 6.3K token ≈ 10 秒）；
        //   2) 这个模型本来就是按文本 <tool_call> 约定微调的，提示词教它走原生反而互相打架。
        // AppConfigStore 里 TOOLCALL_AUTO 的注释一直就是这么写的，只是这里没实现
        // （native 分支只看 prefersTextToolCalls()，而 OpenAI 兼容适配器恒为 false），
        // 所以线上跑的一直是「下发 tools（被无视）+ 提示词不教文本格式」这个最差组合。
        if (configStore != null && configStore.isLocalMode()) {
            return false;
        }
        return adapter == null || (!adapter.prefersTextToolCalls() && !rejected);
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
        List<ChatMessage> messages = buildMessages(sessionId, mode, userMessage);

        // 3.5 构建工具定义列表（仅原生 function calling 模式下随请求下发）
        boolean nativeTools = useNativeTools();
        List<Map<String, Object>> toolDefinitions = nativeTools ? buildToolDefinitions(mode) : List.of();
        log.info("可用工具数量: {}（工具调用方式: {}）", toolDefinitions.size(),
            nativeTools ? "原生 function calling" : "文本 <tool_call> 约定");

        // 4. 工具调用循环（无轮次上限，直到模型给出最终答案）
        ModelAdapter adapter = adapterManager.getActiveAdapter();
        int round = 0;
        int repairs = 0;   // 残缺工具调用 / 空响应的纠正次数（有上限，防止死循环）

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
                soundNotifier.play(SoundNotifier.Kind.ERROR);   // 任务被中止：出错误提示音
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
                soundNotifier.play(SoundNotifier.Kind.ERROR);   // 模型调用失败：出错误提示音
                return "模型调用失败: " + e.getMessage();
            }

            eventStore.recordEvent(sessionId, LionEvent.EventType.MODEL_RESPONSE,
                Map.of("content", truncate(response.content(), 200), 
                       "hasToolCalls", response.toolCalls() != null && !response.toolCalls().isEmpty()),
                "模型响应");

            // 6. 如果没有工具调用，检查文本中是否有XML/JSON格式的工具调用
            List<ChatMessage.ToolCall> toolCalls = response.toolCalls();
            String responseContent = response.content() != null ? response.content() : "";
            if (toolCalls != null && !toolCalls.isEmpty()) {
                log.info("从响应中拿到 {} 个原生工具调用", toolCalls.size());
            }
            
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
                // 7.1 先判断这是不是「模型想做工具调用但调用残缺」或者「整轮什么都没输出」。
                //     这两种情况都不能当成正常收尾 —— 否则用户会收到一句空白回答、
                //     任务被静默结束。纠正一次再试，最多 MAX_CALL_REPAIR 次。
                boolean malformed = response.malformedToolCall();
                if (repairs < MAX_CALL_REPAIR && (malformed || responseContent.isBlank())) {
                    repairs++;
                    log.warn("本轮没有可执行的工具调用（残缺={}, 正文长度={}），第 {} 次纠正重试 - 会话: {}",
                        malformed, responseContent.length(), repairs, sessionId);
                    eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                        Map.of("malformed", malformed, "repair", repairs),
                        malformed ? "残缺工具调用，已纠正重试" : "空响应，已纠正重试");
                    conversationHistory.addMessage(ConversationMessage.system(sessionId, repairHint(malformed)));
                    messages = buildMessages(sessionId, mode, userMessage);
                    continue;
                }

                String finalAnswer = responseContent;
                
                // 保存助手消息
                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, finalAnswer));
                
                log.info("=== Agent主循环结束 === 共 {} 轮工具调用", round - 1);
                soundNotifier.play(SoundNotifier.Kind.DONE);     // 任务完成：成功提示音
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
                    soundNotifier.play(SoundNotifier.Kind.ERROR);   // 任务被中止：出错误提示音
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
            messages = buildMessages(sessionId, mode, userMessage);
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

                List<ChatMessage> messages = buildMessages(sessionId, mode, userMessage);
                ModelAdapter adapter = adapterManager.getActiveAdapter();

                // 构建工具定义列表（仅原生 function calling 模式下随请求下发）
                List<Map<String, Object>> toolDefinitions = useNativeTools()
                    ? buildToolDefinitions(mode) : List.of();

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
                                // 先判断是不是「模型想做工具调用但调用残缺」或「整轮什么都没输出」。
                                // 这两种都不能当成正常收尾（否则用户收到一句空白回答、任务被静默结束），
                                // 纠正一次再试，最多 MAX_CALL_REPAIR 次。
                                // 累积器非空 = 收到了 tool_calls 分片，但没能拼出可执行的调用。
                                boolean malformed = !toolCallAccumulators.isEmpty();
                                if (malformed || fullContent.isBlank()) {
                                    // 这是首轮，纠正预算从 1 开始用（上限 MAX_CALL_REPAIR）
                                    int next = 1;
                                    log.warn("本轮没有可执行的工具调用（残缺={}, 正文长度={}），"
                                        + "第 {} 次纠正重试 - 会话: {}",
                                        malformed, fullContent.length(), next, sessionId);
                                    eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                                        Map.of("malformed", malformed, "repair", next),
                                        malformed ? "残缺工具调用，已纠正重试" : "空响应，已纠正重试");
                                    conversationHistory.addMessage(
                                        ConversationMessage.system(sessionId, repairHint(malformed)));
                                    sink.next(AgentChunk.text(malformed
                                        ? "\n\n⚠ 上次的工具调用不完整，正在重试…\n\n"
                                        : "\n\n⚠ 上次没有返回内容，正在重试…\n\n"));
                                    List<ChatMessage> retryMessages = buildMessages(sessionId, mode, userMessage);
                                    processStreamRound(sink, sessionId, retryMessages, model, thinkingLevel,
                                        adapter, toolDefinitions, mode, userMessage, 1, next);
                                    return;
                                }
                                // 没有工具调用，流结束
                                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, fullContent,
                                    reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null));
                                soundNotifier.play(SoundNotifier.Kind.DONE);     // 任务完成：成功提示音
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
                                List<ChatMessage> nextMessages = buildMessages(sessionId, mode, userMessage);
                                processStreamRound(sink, sessionId, nextMessages, model, thinkingLevel, 
                                    adapter, toolDefinitions, mode, userMessage, 1);
                            }
                        } catch (Exception e) {
                            log.error("流式处理工具调用失败", e);
                            soundNotifier.play(SoundNotifier.Kind.ERROR);   // 流式处理异常：出错误提示音
                            sink.error(e);
                        }
                    })
                    .doOnError(e -> {
                        log.error("流式调用失败", e);
                        soundNotifier.play(SoundNotifier.Kind.ERROR);   // 模型流式调用失败：出错误提示音
                        sink.error(e);
                    })
                    .subscribe();
                    
            } catch (Exception e) {
                soundNotifier.play(SoundNotifier.Kind.ERROR);   // 流式入口异常：出错误提示音
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
     * @param userMessage 原始用户消息（技能适用性判断用）
     * @param currentRound 当前轮次（仅用于日志，无上限）
     */
    private void processStreamRound(reactor.core.publisher.FluxSink<AgentChunk> sink,
                                      String sessionId, List<ChatMessage> messages, String model,
                                      ThinkingLevel thinkingLevel, ModelAdapter adapter,
                                      List<Map<String, Object>> toolDefinitions, AgentMode mode,
                                      String userMessage, int currentRound) {
        processStreamRound(sink, sessionId, messages, model, thinkingLevel, adapter,
            toolDefinitions, mode, userMessage, currentRound, 0);
    }

    /**
     * 流式轮次（带纠正计数）
     *
     * @param repairRound 已经用掉的「残缺工具调用 / 空响应」纠正次数，上限 MAX_CALL_REPAIR
     */
    private void processStreamRound(reactor.core.publisher.FluxSink<AgentChunk> sink,
                                      String sessionId, List<ChatMessage> messages, String model,
                                      ThinkingLevel thinkingLevel, ModelAdapter adapter,
                                      List<Map<String, Object>> toolDefinitions, AgentMode mode,
                                      String userMessage, int currentRound, int repairRound) {
        // 控制检查：暂停时阻塞等待，停止时中止流
        try {
            agentControl.checkControl(sessionId);
        } catch (AgentControlManager.AgentStoppedException e) {
            log.info("流式任务已手动停止: {} (轮次 {})", sessionId, currentRound);
            soundNotifier.play(SoundNotifier.Kind.ERROR);   // 流式任务被中止：出错误提示音
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
                        soundNotifier.play(SoundNotifier.Kind.DONE);     // 任务完成：成功提示音
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

                        List<ChatMessage> nextMessages = buildMessages(sessionId, mode, userMessage);
                        // 纠正计数原样带过去：整条用户消息共用一份纠正预算，防止反复重试
                        processStreamRound(sink, sessionId, nextMessages, model, thinkingLevel, 
                            adapter, toolDefinitions, mode, userMessage, currentRound + 1, repairRound);
                    }
                } catch (Exception e) {
                    log.error("流式处理工具调用失败 (轮次 {})", currentRound, e);
                    soundNotifier.play(SoundNotifier.Kind.ERROR);   // 流式处理异常：出错误提示音
                    sink.error(e);
                }
            })
            .doOnError(e -> {
                log.error("流式调用失败 (轮次 {})", currentRound, e);
                soundNotifier.play(SoundNotifier.Kind.ERROR);   // 模型流式调用失败：出错误提示音
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

        // 审批策略检查：禁止/需确认的工具直接拒绝并反馈给模型
        ApprovalPolicy.ApprovalResult approval = approvalPolicy.checkApproval(tool.getId());
        if (approval.action() == ApprovalPolicy.ApprovalAction.BLOCK
                || approval.action() == ApprovalPolicy.ApprovalAction.CONFIRM) {
            String error = approval.reason() != null ? approval.reason()
                : "工具 " + toolName + " 需要用户确认后才能执行";
            log.warn("审批拦截: {} ({})", toolName, approval.action().getDisplayName());
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", error, "approval", approval.action().name()),
                "审批拦截: " + toolName);
            soundNotifier.play(SoundNotifier.Kind.APPROVAL);   // 需要审批：提醒音
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, "错误: " + error));
            return;
        }

        // 权限检查：工具所需权限不得高于会话工作区的授予权限
        if (!checkPermission(sessionId, tool)) {
            String error = "工具 " + toolName + " 需要更高权限（当前工作区权限等级不足，"
                + "可在工作区设置中提升权限）";
            log.warn(error);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", error), error);
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, "错误: " + error));
            return;
        }

        // 执行工具（设置上下文：相对路径基于绑定的工作区解析；
        // 会话 ID 也一起放进去——ask_user 这类工具需要知道自己在哪个会话里）
        sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .ifPresent(ws -> com.lioncode.core.workspace.WorkspaceContext.set(ws.path()));
        com.lioncode.core.session.SessionContext.set(sessionId);
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
            com.lioncode.core.session.SessionContext.clear();
        }
    }

    /**
     * 权限检查：工具所需权限等级 vs 会话绑定工作区的授予权限
     * 
     * READ_ONLY 工作区只能执行只读工具；
     * WORKSPACE_WRITE 可执行只读+工作区写工具（不可执行 FULL_ACCESS 工具）；
     * FULL_ACCESS 全部放行。未绑定工作区时不限制（保持兼容）。
     */
    private boolean checkPermission(String sessionId, ToolPlugin tool) {
        ToolPlugin.PermissionLevel required = tool.getRequiredPermission();
        var grantedOpt = sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .map(com.lioncode.core.workspace.WorkspaceManager.Workspace::permission);
        if (grantedOpt.isEmpty()) {
            return true;
        }
        return switch (grantedOpt.get()) {
            case READ_ONLY -> required == ToolPlugin.PermissionLevel.READ_ONLY;
            case WORKSPACE_WRITE -> required != ToolPlugin.PermissionLevel.FULL_ACCESS;
            case FULL_ACCESS -> true;
        };
    }

    /**
     * 构建模型消息列表
     */
    private List<ChatMessage> buildMessages(String sessionId, AgentMode mode, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();

        // 会话绑定的工作区路径
        String workspacePath = sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .map(ws -> ws.path())
            .orElse(null);

        // 系统提示词（含模式专属提示词与适用技能的能力提示词）
        String systemPrompt = buildSystemPrompt(mode, workspacePath, userMessage, useNativeTools());
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
     *
     * @param nativeTools true = 本轮走原生 function calling（工具定义已随请求下发），
     *                    提示词只把文本 &lt;tool_call&gt; 当兜底格式说明；
     *                    false = 靠文本约定，完整描述 JSON/XML 两种格式与示例
     */
    private String buildSystemPrompt(AgentMode mode, String workspacePath, String userMessage,
                                     boolean nativeTools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 Lion-Code Agent，通过调用工具真实操作文件和命令来完成任务。\n\n");

        // 当前工作区：所有文件操作和命令执行都在此工作区内进行
        prompt.append("## 工作区\n");
        prompt.append(workspacePath != null ? workspacePath : "(未设置)").append("\n");
        prompt.append("文件与命令都在此工作区内；path 可用相对路径（相对工作区）或绝对路径。\n\n");

        // 根据模式添加专属提示词（每个模式独立撰写，行为规则各不相同）
        prompt.append(modeInstructions(mode));

        // 注入适用技能的领域能力提示词（按用户消息匹配）
        prompt.append(buildSkillPrompt(userMessage));

        // 输出纪律：本地模型约 10.8 token/s，一句话能交代的事写成一段就是几十秒。
        // 这几条集中放在一起（散着写模型会挑着遵守），顺序按"影响速度"排。
        prompt.append("\n## 输出纪律（直接影响速度，必须守）\n");
        prompt.append("1. 正文极简：一轮最多两句话（≤60 字）。不解释背景、不罗列计划、不复述文件内容、不重复工具结果。\n");
        prompt.append("2. 要动手就直接动手：不要写“我这就去读取/修改…”这类过渡句，直接给工具调用。\n");
        prompt.append("3. 一轮只给一个工具调用；需要多步就分成多轮，宁可多走一轮，也不要在一轮里塞多个调用。\n");
        prompt.append("4. 工具结果回来后：能用一句话回答就回答，要继续做就直接调下一个工具，不要总结过程。\n");
        prompt.append("5. 不输出思考过程、不写“第一步/第二步”的规划清单、不复述工具参数。\n\n");

        // 工具清单：只给「名字 + 一句用途」。
        // 本地 llama-server 没开 --jinja，tools 定义发过去会被服务端丢掉，
        // 所以这份清单就是模型能看到的**唯一**工具说明：名字不能省（省了它就开始编造工具），
        // 但描述要砍到一句话 —— 53 个工具的长描述累积起来是几百 token 的白烧。
        List<ToolPlugin> tools = pluginRegistry.getToolsByMode(mode);
        if (!tools.isEmpty()) {
            prompt.append("## 可用工具（").append(tools.size()).append(" 个）\n");
            for (ToolPlugin tool : tools) {
                prompt.append("- ").append(tool.getName()).append(": ")
                      .append(shortDescription(tool.getDescription())).append("\n");
            }
            prompt.append("\n");

            prompt.append("## 工具调用格式\n");
            if (nativeTools) {
                // 原生 function calling：工具定义已随请求下发，让模型走 API 的工具通道。
                //
                // 【千万别在这里放文本格式的代码块示例】—— 实测 MiMo 会因此把两种机制
                // 混在一起，返回一个残缺的原生调用（name=null、arguments="{}"、
                // finish_reason=stop），工具直接跑不起来。去掉示例后同一请求立刻正常。
                // 所以这里只做一句说明，不给范例。
                prompt.append("工具定义已随本次请求下发：把调用放在 tool_calls 里返回，不要写成正文文字。\n");
                prompt.append("arguments 必须是合法 JSON 对象，参数名取自工具定义的 parameters，不要自己发明。\n\n");
            } else {
                prompt.append("首选 JSON：\n");
                prompt.append("<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.txt\"}}\n</tool_call>\n\n");
                prompt.append("也认 XML：\n");
                prompt.append("<tool_call>\n<name>read_file</name><arguments>{\"path\": \"a.txt\"}</arguments>\n</tool_call>\n\n");
                prompt.append("- arguments 必须是合法 JSON；参数名只用上面工具里的，不要发明参数。\n");
                prompt.append("- 调用写进 <tool_call> 里，正文可以只有一句话，紧跟调用即可。\n\n");
            }
        }

        prompt.append("## 必须用工具的情形\n");
        prompt.append("读/写/改/删文件、执行命令、看目录、搜内容、Git 操作、查系统信息 —— 一律调工具，不许只给建议。\n");

        return prompt.toString();
    }

    /**
     * 工具描述砍到一句话：合并空白，遇到第一个句号/分号就截断，最长 48 字。
     *
     * 清单只用来告诉模型"有哪些工具"，细节在微调时已经学过；
     * 原文照抄会把几百 token 的说明塞进每一轮请求里。
     */
    private static String shortDescription(String description) {
        if (description == null) {
            return "";
        }
        String s = description.replaceAll("\\s+", " ").trim();
        int cut = s.length();
        for (String sep : new String[] { "。", "；", ". ", "; " }) {
            int i = s.indexOf(sep);
            if (i > 0 && i < cut) {
                cut = i;
            }
        }
        if (cut > 48) {
            cut = 48;
        }
        return s.substring(0, Math.min(cut, s.length())).trim();
    }

    /**
     * 提示词体检用：把这一轮真正会发出去的系统提示词原样返回。
     *
     * 不另写一份示例，走的就是真实链路（同一个 buildSystemPrompt + 同一个 useNativeTools），
     * 否则量出来的数字没意义。见 GET /api/runtime/prompt-preview。
     */
    public String previewSystemPrompt(AgentMode mode, String workspacePath, String userMessage) {
        return buildSystemPrompt(mode, workspacePath, userMessage, useNativeTools());
    }

    /** 提示词体检用：当前这一轮走原生 function calling 还是文本 &lt;tool_call&gt; 约定 */
    public boolean previewUsesNativeTools() {
        return useNativeTools();
    }

    /** 提示词体检用：原生通道下才会随请求下发的 tools 定义（用来量"不下发能省多少 token"） */
    public java.util.List<java.util.Map<String, Object>> previewToolDefinitions(AgentMode mode) {
        return buildToolDefinitions(mode);
    }


    /**
     * 各工作模式的专属提示词
     * 
     * 四种模式分别撰写独立的、完整的、可区分的提示词，
     * 让模型在行为风格、执行流程、工具选择上产生真实差异。
     */
    private String modeInstructions(AgentMode mode) {
        return switch (mode) {
            case STANDARD -> """
                ## 当前模式：标准模式（STANDARD）

                你是全能型编程助手，拥有完整工具集（文件、Shell、Git、网络、代码工具）。

                行为准则：
                1. 收到任务先快速分析，然后立即动手执行，绝不停留在口头建议。
                2. 优先用工具实际读取、修改、运行代码，用真实结果说话。
                3. 每轮只调用一个工具；拿到结果后，根据结果决定下一步。
                4. 修改文件前先读取相关文件，修改后主动验证（编译/运行/测试）。
                5. 输出保持结构化：简短说明 → 工具调用 → 结果总结。
                6. 遇到错误时，先读错误信息再定位原因，不要盲目重试。

                """;

            case PTC -> """
                ## 当前模式：PTC 预规划模式（Plan-Then-Code）

                本模式下你**必须先规划、后执行**，严格按以下流程：

                第一步：输出完整执行计划（用 Markdown 编号列表）：
                - 目标拆解
                - 每一步要调用的工具及其目的
                - 风险点与验证方式
                计划输出完毕后，再开始调用工具。

                第二步：按计划**逐步执行**，每执行一步：
                - 只调用一个工具
                - 拿到结果后对照计划核对进度
                - 如结果与预期不符，先修正计划再继续

                第三步：全部步骤完成后，输出执行总结（计划 vs 实际）。

                禁止：跳过计划直接动手；一次调用多个工具；计划被打乱后不回头对照。

                """;

            case CREATIVE -> """
                ## 当前模式：创造模式（CREATIVE）

                本模式用于**扩展 Lion-Code 自身能力**：编写、修改、安装、卸载插件。

                你的创造空间：
                1. 新插件源码写入工作区 `.lioncode/plugins/` 目录，按 ToolPlugin / SkillPlugin 接口实现。
                2. 可以读取项目现有插件源码（core/plugin 目录）作为实现参考。
                3. 可以使用全部工具（文件、Shell、Git、网络、代码工具），鼓励探索性方案。
                4. 修改完插件后用 Shell 编译验证（如 mvn compile）。
                5. 安装/卸载由运行时插件注册表管理，你负责产出高质量插件代码与说明。

                行为准则：
                - 大胆创造，但保证代码可编译、可测试。
                - 每次只调用一个工具，逐步构建，不要试图一步到位。
                - 交付时说明插件功能、用法和安装方式。

                """;

            case MINIMAL -> """
                ## 当前模式：极简模式（MINIMAL）

                本模式仅开放**文件工具和 Shell 工具**，追求最少步骤、最高效率。

                行为准则：
                1. 只使用 read_file / write_file / modify_file / list_files / execute_command 等
                   文件与 Shell 工具，不得调用其他任何工具。
                2. 回复务必简短：不解释背景、不寒暄、不说废话，直接执行。
                3. 能用一条命令完成的事，不要拆成多条。
                4. 每轮只调用一个工具；结果返回后立即决定下一步。
                5. 输出风格：一句话目标 → 工具调用 → 结果（如有错误，一句话说明并修复）。

                """;
        };
    }

    /**
     * 构建技能提示词片段
     * 
     * 扫描所有已注册的Skill技能插件，注入适用于当前用户消息的技能能力提示词，
     * 让模型按领域最佳实践工作（此前技能仅注册但从未参与提示词构建）。
     */
    private String buildSkillPrompt(String userMessage) {
        StringBuilder prompt = new StringBuilder();
        List<SkillPlugin> applicable = pluginRegistry.getSkillPlugins().stream()
            .filter(skill -> {
                try {
                    return userMessage != null && skill.isApplicable(userMessage);
                } catch (Exception e) {
                    log.warn("技能适用性判断异常: {}", skill.getId(), e);
                    return false;
                }
            })
            .toList();

        if (applicable.isEmpty()) {
            return "";
        }

        prompt.append("\n## 激活的领域技能\n\n");
        for (SkillPlugin skill : applicable) {
            String fragment = skill.getSystemPromptFragment();
            if (fragment == null || fragment.isBlank()) {
                continue;
            }
            prompt.append("### ").append(skill.getName()).append("\n\n");
            prompt.append(fragment.trim()).append("\n\n");
        }
        log.debug("已注入 {} 个技能提示词片段", applicable.size());
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

        // 宽容兜底：模型漏写 <tool_call> 包裹，直接给 <name>..</name><arguments>{..}</arguments>。
        // 实测云端模型（MiMo）在文本模式下就会这么吐，不认的话整轮工具调用直接丢掉。
        // 这个模式在正常正文里几乎不可能出现，误判风险很低。
        if (toolCalls.isEmpty()) {
            Pattern loosePattern = Pattern.compile(
                "(?s)<name>\\s*([A-Za-z_][\\w.\\-]*)\\s*</name>\\s*"
                + "<arguments>\\s*(\\{.*?\\}|\\[.*?\\])\\s*</arguments>");
            Matcher looseMatcher = loosePattern.matcher(text);
            while (looseMatcher.find()) {
                String name = looseMatcher.group(1).trim();
                String argsStr = looseMatcher.group(2).trim();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(argsStr, Map.class);
                    String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    toolCalls.add(new ChatMessage.ToolCall(id, name, parsed));
                    log.info("解析到未包裹 <tool_call> 的 XML 工具调用: {} {}", name, argsStr);
                } catch (Exception e) {
                    log.debug("宽松XML工具调用参数解析失败: {} {}", name, argsStr);
                }
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
