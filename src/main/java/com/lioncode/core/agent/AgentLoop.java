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

    /**
     * 「别再来一遍」守卫：同样的调用重复做、失败后硬试，都在这里拦。
     * 见 ToolCallGuard 类注释里那次真实跑测试的数据（create_directory ×7、fetch_url 失败 ×45）。
     */
    private final ToolCallGuard toolGuard = new ToolCallGuard();

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

    /** 一条用户消息最多允许多少轮工具调用（跑飞时明确报错，别拖到前端超时） */
    // 轮次上限：只是防跑飞的兜底。一轮最多 3 个调用，200 轮 = 600 个调用，够长任务用；
    // 真正的死循环由 ToolCallGuard（同参数重复 5 次即终止）负责，不该靠砍任务来"防"。
    private static final int MAX_TOOL_ROUNDS = 200;

    /**
     * 一轮里最多执行几个工具调用。
     *
     * 【这是"慢"的最大结构性原因】本机解码只有 11-12 token/s（实测：89 ms/token），
     * 以前硬性"一轮只准一个工具"，50 个工具就是 50 轮 —— 每轮 2-5 秒预填充
     * 加 4-5 秒生成，合计约 7 分钟，用户体感就是"模型太慢"。
     * 现在允许一轮 3 个互不依赖的调用，轮数直接砍到 1/3，等价于 3 倍速，
     * 而且不牺牲正确性：工具仍然严格按顺序执行、每个结果都单独回给模型
     * （所以有依赖的多步任务照样安全，只是提示词要求它这种时候一轮只给一个）。
     */
    private static final int MAX_TOOLS_PER_ROUND = 3;

    /** 本地模型一轮最多生成这么多 token（服务器默认 -n 4096 ≈ 6 分 20 秒，太长）。 */
    private static final int LOCAL_MAX_TOKENS_PER_ROUND = 1024;

    /**
     * 每轮生成上限的天花板：写大文件（create_file 里带几百行内容）本来就要更多 token，
     * 撞上限只会把 tool_call 截断成残缺调用、白烧一整轮。所以撞到 length 就翻倍：1024 → 2048 → 4096。
     */
    private static final int MAX_TOKENS_CEILING = 4096;

    /** 会话 → 当前每轮生成长度上限（撞到 length 临时升档，下一条用户消息复位）。 */
    private final java.util.Map<String, Integer> roundCapBySession =
        new java.util.concurrent.ConcurrentHashMap<>();

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
        // 【本地盒子运行时走文本通道】—— 2026-09-29 抓包实测的结论，别凭印象改。
        //
        // 原生通道在"一次只调一个工具"时没问题，但要让模型批量省时间（一次给 2-3 个调用），
        // 本机 llama-server 会把模型吐的多个 <tool_call> 块**揉成一个调用**，把后续块的 XML
        // 塞进第一个调用的 arguments 里，抓包抓到的原文长这样：
        //   delete_file arguments = {"path":"test.txt\n</parameter></function>\n</tool_call>
        //                           <tool_call>\n<function=change_permissions>…
        // 这不是合法 JSON → 整轮作废（"参数不是合法JSON（残缺调用）"）→
        // 每轮 91 秒（1024 token × 11 tok/s）白烧，连着三次之后任务就停住 —— 用户看到的
        // "花了 6 分钟还中断了"就是这么来的。这是服务端解析的问题，我们改不了。
        //
        // 文本通道下模型的输出是纯文本，多个块由我们自己的 QwenToolCallParser 解析，
        // 服务端碰不到它。实测（同一个模型、同一条指令）：
        //   不下发 tools + 提示词给出批量示例 → 一轮干净地回 3 个块，52 token，7.7 秒
        // 而且更省：模板渲染的 tools 段约 3K token，我们自己的清单只要约 1.4K。
        //
        // 自定义 API（云端）仍然走原生 —— 它们按标准解析多个调用，没有这个毛病。
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
        toolGuard.reset(sessionId);   // 新的一条用户消息：重复调用/连续失败计数清零
        roundCapBySession.remove(sessionId);   // 生成上限也复位（上一条消息升过档不影响新任务）

        // 3. 构建消息列表
        List<ChatMessage> messages = buildMessages(sessionId, mode, userMessage);

        // 3.5 构建工具定义列表（仅原生 function calling 模式下随请求下发）
        boolean nativeTools = useNativeTools();
        List<Map<String, Object>> toolDefinitions = nativeTools ? buildToolDefinitions(mode) : List.of();
        // 注意别打印 toolDefinitions.size()：文本模式下这一坨按设计就是空的（不下发 tools），
        // 那样日志会写成"可用工具数量: 0"，看起来像工具全丢了（实际提示词里有 54 个）。
        log.info("本轮可用工具 {} 个（随请求下发 {} 个；工具调用方式: {}）",
            pluginRegistry.getToolsByMode(mode).size(), toolDefinitions.size(),
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
                response = adapter.chatWithOptions(messages, model, thinkingLevel, toolDefinitions, null,
                    maxTokensPerRound(sessionId));
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

            // 撞到生成长度上限：本轮很可能被截断（大文件写入），把下一轮上限翻倍
            if (response.finishReason() != null && response.finishReason().toLowerCase().contains("length")) {
                bumpRoundCap(sessionId);
            }

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

            // 6.5 一轮最多 MAX_TOOLS_PER_ROUND 个工具调用（多余的丢弃并提示模型）
            int toolCountBeforeCap = toolCalls.size();
            toolCalls = capToolsPerRound(sessionId, toolCalls);
            boolean droppedExtraTools = toolCalls.size() < toolCountBeforeCap;

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
                    addNotice(sessionId, repairHint(malformed));
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
                    ToolCallGuard.Decision decision =
                        toolGuard.beforeCall(sessionId, toolCall.name(), argsFingerprint(toolCall.arguments()));
                    if (decision.verdict() == ToolCallGuard.Verdict.ABORT) {
                        log.warn("重复调用终止任务: {} (第 {} 次)", toolCall.name(), decision.count());
                        eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                            Map.of("error", "重复调用", "tool", toolCall.name()),
                            "重复调用，已终止");
                        soundNotifier.play(SoundNotifier.Kind.ERROR);
                        conversationHistory.addMessage(ConversationMessage.assistant(sessionId, decision.hint()));
                        return decision.hint();
                    }
                    if (decision.verdict() == ToolCallGuard.Verdict.SKIP) {
                        log.info("跳过重复/连续失败的调用: {} (第 {} 次)", toolCall.name(), decision.count());
                        conversationHistory.addMessage(
                            ConversationMessage.toolResult(sessionId, toolCall.id(), toolCall.name(),
                                "（未执行）" + decision.hint()));
                        continue;
                    }
                    if (decision.hint() != null) {
                        addNotice(sessionId, decision.hint());
                    }
                    toolGuard.afterCall(sessionId, toolCall.name(),
                        executeTool(sessionId, toolCall, mode));
                }
            }

            // 9.5 单工具限制提示：告诉模型多余的调用被忽略了
            if (droppedExtraTools) {
                conversationHistory.addMessage(ConversationMessage.user(sessionId,
                    "【系统提示】一轮最多 3 个工具调用。你上次一次返回了更多，多余的已被忽略，"
                    + "本轮只执行了前 3 个。互不依赖的调用可以一轮一起给（最多 3 个），有依赖的请一轮给一个。"));
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
        toolGuard.reset(sessionId);   // 新的一条用户消息：重复调用/连续失败计数清零
        roundCapBySession.remove(sessionId);   // 生成上限也复位（上一条消息升过档不影响新任务）

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

                // 流式最后一片带的 finish_reason：用来判断这一轮是不是被长度上限截断了
                java.util.concurrent.atomic.AtomicReference<String> lastFinish =
                    new java.util.concurrent.atomic.AtomicReference<>();
                
                adapter.chatStream(messages, model, thinkingLevel, toolDefinitions, maxTokensPerRound(sessionId))
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
                        if (chunk.finishReason() != null) {
                            lastFinish.set(chunk.finishReason());
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            String fullContent = contentBuilder.toString();

                            // 撞到长度上限：下一轮上限翻倍（写大文件本来就需要更多 token）
                            String fr = lastFinish.get();
                            if (fr != null && fr.toLowerCase().contains("length")) {
                                bumpRoundCap(sessionId);
                            }
                            
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
                                        ConversationMessage.user(sessionId, repairHint(malformed)));
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
                                // 一轮最多 MAX_TOOLS_PER_ROUND 个工具调用
                                int toolCountBeforeCap = toolCalls.size();
                                toolCalls = capToolsPerRound(sessionId, toolCalls);
                                boolean droppedExtraTools = toolCalls.size() < toolCountBeforeCap;

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
                                        ToolCallGuard.Decision decision = toolGuard.beforeCall(sessionId,
                                            toolCall.name(), argsFingerprint(toolCall.arguments()));
                                        if (decision.verdict() == ToolCallGuard.Verdict.ABORT) {
                                            log.warn("重复调用终止任务: {} (第 {} 次)",
                                                toolCall.name(), decision.count());
                                            eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                                                Map.of("error", "重复调用", "tool", toolCall.name()),
                                                "重复调用，已终止");
                                            soundNotifier.play(SoundNotifier.Kind.ERROR);
                                            conversationHistory.addMessage(
                                                ConversationMessage.assistant(sessionId, decision.hint()));
                                            sink.next(AgentChunk.text(decision.hint()));
                                            sink.complete();
                                            return;
                                        }
                                        if (decision.verdict() == ToolCallGuard.Verdict.SKIP) {
                                            log.info("跳过重复/连续失败的调用: {} (第 {} 次)",
                                                toolCall.name(), decision.count());
                                            conversationHistory.addMessage(
                                                ConversationMessage.toolResult(sessionId, toolCall.id(),
                                                    toolCall.name(), "（未执行）" + decision.hint()));
                                            sink.next(AgentChunk.toolCall(toolCall.name(), "已跳过（重复调用）"));
                                            continue;
                                        }
                                        if (decision.hint() != null) {
                                            conversationHistory.addMessage(
                                                ConversationMessage.user(sessionId, decision.hint()));
                                        }
                                        toolGuard.afterCall(sessionId, toolCall.name(),
                                            executeTool(sessionId, toolCall, mode));
                                        // 发送工具执行结果通知
                                        sink.next(AgentChunk.toolCall(toolCall.name(), "执行完成"));
                                    }
                                }

                                // 单工具限制提示
                                if (droppedExtraTools) {
                                    conversationHistory.addMessage(ConversationMessage.user(sessionId,
                                        "【系统提示】一轮最多 3 个工具调用。你上次一次返回了更多，多余的已被忽略，"
                                        + "本轮只执行了前 3 个。互不依赖的调用可以一轮一起给（最多 3 个），有依赖的请一轮给一个。"));
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

        // 轮次上限：本地模型一轮几十秒，真跑飞了宁可明确报错，
        // 也不要让前端的 10 分钟等待超时来兜底（那样用户只看到"❌ 处理超时"，不知道卡在哪）
        if (currentRound > MAX_TOOL_ROUNDS) {
            String msg = "⏹ 工具调用轮次过多（" + currentRound + " 轮），已停止。"
                + "任务可能陷入了重复尝试，建议把要求拆小一点再试。";
            log.warn(msg + " 会话: {}", sessionId);
            eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                Map.of("error", "轮次过多", "round", currentRound), "轮次过多已停止");
            soundNotifier.play(SoundNotifier.Kind.ERROR);
            conversationHistory.addMessage(ConversationMessage.assistant(sessionId, msg));
            sink.next(AgentChunk.text(msg));
            sink.complete();
            return;
        }

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
        java.util.concurrent.atomic.AtomicReference<String> lastFinish =
            new java.util.concurrent.atomic.AtomicReference<>();

        adapter.chatStream(messages, model, thinkingLevel, toolDefinitions, maxTokensPerRound(sessionId))
            .doOnNext(chunk -> {
                if (chunk.deltaContent() != null && !chunk.deltaContent().isEmpty()) {
                    contentBuilder.append(chunk.deltaContent());
                    sink.next(AgentChunk.text(chunk.deltaContent()));
                }
                // 累积思考内容（thinking模式回传）
                if (chunk.reasoningContentDelta() != null && !chunk.reasoningContentDelta().isEmpty()) {
                    reasoningBuilder.append(chunk.reasoningContentDelta());
                }
                if (chunk.finishReason() != null) {
                    lastFinish.set(chunk.finishReason());
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

                    // 撞到长度上限：下一轮上限翻倍（写大文件本来就需要更多 token）
                    String fr = lastFinish.get();
                    if (fr != null && fr.toLowerCase().contains("length")) {
                        bumpRoundCap(sessionId);
                    }
                    
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
                        // 一轮最多 MAX_TOOLS_PER_ROUND 个工具调用
                        int toolCountBeforeCap = toolCalls.size();
                        toolCalls = capToolsPerRound(sessionId, toolCalls);
                        boolean droppedExtraTools = toolCalls.size() < toolCountBeforeCap;

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
                            conversationHistory.addMessage(ConversationMessage.user(sessionId,
                                "【系统提示】一轮最多 3 个工具调用。你上次一次返回了更多，多余的已被忽略，"
                                + "本轮只执行了前 3 个。互不依赖的调用可以一轮一起给（最多 3 个），有依赖的请一轮给一个。"));
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
    /**
     * 执行一次工具调用。
     *
     * @return true = 工具真的跑成功了；false = 没找到工具 / 模式或权限不允许 / 执行失败
     *         （返回值喂给 {@link ToolCallGuard}，用来发现"某个工具在连续失败还硬试"）
     */
    private boolean executeTool(String sessionId, ChatMessage.ToolCall toolCall, AgentMode mode) {
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
            // 模型偶尔会发明工具名（实测它调用过 delete_directory_placeholder —— 没有这个工具）。
            // 只回一句"未找到工具"它下一轮还会试别的；带上最接近的现有工具，它基本能一次改对。
            String suggest = suggestToolNames(toolName, mode);
            String error = "未找到工具: " + toolName + suggest;
            log.error(error);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", error), error);
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, "错误: " + error));
            return false;
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
            return false;
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
            return false;
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
            return false;
        }

        // 执行工具（设置上下文：相对路径基于绑定的工作区解析；
        // 会话 ID 也一起放进去——ask_user 这类工具需要知道自己在哪个会话里）
        sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .ifPresent(ws -> com.lioncode.core.workspace.WorkspaceContext.set(ws.path()));
        com.lioncode.core.session.SessionContext.set(sessionId);
        try {
            // 【派发前先把参数类型转对】文本通道（本地盒子默认）下所有参数都是字符串，
            // 而工具里写的是 ((Number) args.get("lines")).intValue() → ClassCastException，
            // 实测 glob_files / head_tail_file / directory_tree / modify_file 全中招。
            //
            // 并且**必须带超时**：工具自己卡住时（等网络/凭据/输入）不能再拖住整条任务。
            // 实测：git_remote show 去连远端、git 在等凭据，readAllBytes 一直阻塞，
            // 那条消息卡了 3 分多钟直到用户手动停止。
            ToolResult result = runToolWithTimeout(tool, coerceArguments(tool, toolCall.arguments()),
                toolName, sessionId);
            
            if (result.success()) {
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_COMPLETE,
                    Map.of("toolName", toolName, "result", truncate(result.content(), 500)),
                    "工具调用成功: " + toolName);
                conversationHistory.addMessage(
                    ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, result.content()));
                return true;
            } else {
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                    Map.of("toolName", toolName, "error", result.error()),
                    "工具调用失败: " + toolName);
                conversationHistory.addMessage(
                    ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, 
                        "工具执行错误: " + result.error()));
                return false;
            }
        } catch (Exception e) {
            log.error("工具执行异常: {}", toolName, e);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", e.getMessage()),
                "工具执行异常: " + e.getMessage());
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName,
                    "工具执行异常: " + e.getMessage()));
            return false;
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
                case "system" -> {
                    // 【必须只在最前面】Qwen 的 Jinja 模板（llama-server 现在默认启用 --jinja）
                    // 对夹在中间的 system 消息直接 raise_exception('System message must be at
                    // the beginning')，服务端 500，用户看到的是"模型调用失败"。
                    // 老会话文件里可能已经存了这种消息，所以这里做一道兜底：
                    // 非首条的 system → 降级成 user（带【系统提示】前缀，语义不变）。
                    if (messages.isEmpty()) {
                        messages.add(ChatMessage.system(msg.content()));
                    } else {
                        log.debug("历史里的 system 消息不在开头，已降级为 user 发送（模板不允许）");
                        messages.add(ChatMessage.user("【系统提示】" + msg.content()));
                    }
                }
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
        prompt.append("文件与命令都在此工作区内；path 可用相对路径（相对工作区）或绝对路径。\n");
        // 执行环境：实测模型爱写 Unix 命令，在 Windows 的 cmd 里 ls/cat/rm 都报"'ls' 不是内部或外部命令"。
        // 现在 execute_command 走 PowerShell（ls/cat/rm/cp/mv/pwd 都是内置别名），这里把环境说清楚。
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            prompt.append("执行环境是 **Windows**，execute_command 走 PowerShell：ls/cat/rm/cp/mv/pwd 都能用，");
            prompt.append("但多条命令之间用 `;` 分隔，不要用 `&&`（Windows PowerShell 不认）。\n");
        }
        prompt.append("\n");

        // 根据模式添加专属提示词（每个模式独立撰写，行为规则各不相同）
        prompt.append(modeInstructions(mode));

        // 注入适用技能的领域能力提示词（按用户消息匹配）
        prompt.append(buildSkillPrompt(userMessage));

        // 输出纪律：本地模型约 10.8 token/s，一句话能交代的事写成一段就是几十秒。
        // 这几条集中放在一起（散着写模型会挑着遵守），顺序按"影响速度"排。
        prompt.append("\n## 输出纪律（直接影响速度，必须守）\n");
        prompt.append("1. 正文极简：一轮最多两句话（≤60 字）。不解释背景、不罗列计划、不复述文件内容、不重复工具结果。\n");
        prompt.append("2. 要动手就直接动手：不要写“我这就去读取/修改…”这类过渡句，直接给工具调用。\n");
        // 这一条直接决定"快不快"：本机 11-12 token/s，一轮一个工具 = 50 个工具 50 轮 ≈ 7 分钟。
        prompt.append("3. 互不依赖的调用要一次给（最多 3 个）：同时读几个文件、同时查几样信息，一轮里一起给；\n");
        prompt.append("   有先后依赖的（得先看到结果才知道下一步）就一轮只给一个。一次给超过 3 个会被丢掉。\n");
        prompt.append("4. 工具结果回来后：能用一句话回答就回答，要继续做就直接调下一个工具，不要总结过程。\n");
        prompt.append("5. 不输出思考过程、不写“第一步/第二步”的规划清单、不复述工具参数。\n\n");

        // 工具说明。
        //
        // 原生通道：**不列清单** —— Qwen 的 Jinja 模板会自己把 tools 渲染成 "# Tools" 段
        // （还带 "Required parameters MUST be specified"），我们那份手写清单纯属重复，
        // 白白多烧一千多 token，还可能和模板里的定义打架。
        // 文本通道：清单就是模型能看到的**唯一**工具说明，所以必须列全，
        // 而且要带参数名（1.1.4 里工具第一次调用老失败，就是因为只给了名字和一句描述）。
        List<ToolPlugin> tools = pluginRegistry.getToolsByMode(mode);
        if (!tools.isEmpty()) {
            if (nativeTools) {
                prompt.append("## 可用工具\n");
                prompt.append("本次请求已随消息下发 ").append(tools.size())
                      .append(" 个工具定义（见 # Tools），参数名与必填项以那份定义为准，不要自己发明。\n");
                prompt.append("把调用放在 tool_calls 里返回，不要写成正文文字。\n\n");
            } else {
                prompt.append("## 可用工具（").append(tools.size()).append(" 个）\n");
                prompt.append("括号里是参数名，带 * 的是必填。**参数名必须照抄**，写错或漏必填都会直接调用失败。\n");
                for (ToolPlugin tool : tools) {
                    prompt.append("- ").append(tool.getName()).append(toolSignature(tool)).append(": ")
                          .append(shortDescription(tool.getDescription())).append("\n");
                }
                prompt.append("\n");

                prompt.append("## 工具调用格式\n");
                prompt.append("首选 JSON：\n");
                prompt.append("<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.txt\"}}\n</tool_call>\n\n");
                prompt.append("也认 XML：\n");
                prompt.append("<tool_call>\n<name>read_file</name><arguments>{\"path\": \"a.txt\"}</arguments>\n</tool_call>\n\n");
                // 模型是按 Qwen 模板微调的，它最顺手的其实是下面这种；解析器三种都认，
                // 写清楚是为了让它别在格式上纠结（实测它会先吐一句说明再吐模板格式）。
                prompt.append("也认模板原生格式（**推荐用这个**）：\n");
                prompt.append("<tool_call>\n<function=read_file>\n<parameter=path>a.txt</parameter>\n</function>\n</tool_call>\n\n");
                prompt.append("- 只能用上面清单里的工具名，**不要发明工具**（发明出来的会被直接拒绝）。\n");
                prompt.append("- arguments 必须是合法 JSON；参数名只用上面工具里的，不要发明参数。\n");
                prompt.append("- 调用写进 <tool_call> 里，正文可以只有一句话，紧跟调用即可。\n\n");

                // 批量示例：实测**必须把例子写出来**，模型才会真的一轮给 3 个块；
                // 只写一句"可以一次给多个"它还是只给一个（试过）。
                // 一轮 3 个 = 轮数砍到 1/3，在 11 token/s 的本机就是实打实的 3 倍速。
                prompt.append("## 一次给多个调用（省时间，最多 3 个）\n");
                prompt.append("下面几件事互不依赖时，**连着写多个 <tool_call> 块一次给完**，别一个一个等：\n");
                prompt.append("<tool_call>\n<function=read_file>\n<parameter=path>a.txt</parameter>\n</function>\n</tool_call>\n");
                prompt.append("<tool_call>\n<function=system_info>\n</function>\n</tool_call>\n");
                prompt.append("<tool_call>\n<function=timestamp>\n<parameter=format>%H:%M</parameter>\n</function>\n</tool_call>\n\n");
                prompt.append("有先后依赖的（要先看到结果才知道下一步）仍然一次只给一个；一次超过 3 个会被丢掉。\n\n");
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
     * 工具的参数签名，形如 {@code (path*, content)}，带 * 的是必填。
     *
     * 【为什么必须有这个】一次真实测试（54 个工具逐一调用）里，几乎每个工具都是
     * "第一次失败、第二次成功"，失败原因清一色是 `缺少必需参数: action` / `缺少必需参数: input`
     * —— 因为文本通道下不下发 tools 定义，提示词里又只有工具名和一句描述，
     * 模型根本不知道参数该叫什么，只能猜。一次失败 = 一整轮（预填充+生成，本地 10~40 秒），
     * 参数名这几十个字符，换回来的是几十次往返。
     *
     * @return 形如 "(path*, content)"；拿不到 schema 就返回空串
     */
    private String toolSignature(ToolPlugin tool) {
        try {
            Map<String, Object> def = tool.getFunctionDefinition();
            Object paramsObj = def.get("parameters");
            if (!(paramsObj instanceof Map<?, ?> params)) {
                return "";
            }
            Object propsObj = params.get("properties");
            if (!(propsObj instanceof Map<?, ?> props) || props.isEmpty()) {
                return "";
            }
            java.util.Set<String> required = new java.util.HashSet<>();
            if (propsObj != null && params.get("required") instanceof List<?> reqList) {
                for (Object r : reqList) {
                    if (r != null) {
                        required.add(String.valueOf(r));
                    }
                }
            }
            StringBuilder sb = new StringBuilder("(");
            for (Object keyObj : props.keySet()) {
                String key = String.valueOf(keyObj);
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(key);
                if (required.contains(key)) {
                    sb.append("*");
                }
            }
            return sb.append(")").toString();
        } catch (Exception e) {
            log.debug("取工具参数签名失败: {}", tool.getName(), e);
            return "";
        }
    }

    /**
     * 单个工具的执行上限（秒）。
     *
     * <p>实测教训：`git_remote show origin` 会去连远端，git 在等凭据时**永远不关 stdout**，
     * 而工具里是 `readAllBytes()` 写在 `waitFor(30s)` **前面** —— 于是那个 30 秒超时
     * 形同虚设，整条消息卡了 3 分多钟，用户只能手动点停止。
     * 这里在**派发层**兜一道：任何工具超过这个时间没返回，就当它卡住，回一句可照做的错误，
     * 让模型换别的做法 —— 一条消息绝不会因为某个工具卡死而废掉。
     */
    @org.springframework.beans.factory.annotation.Value("${lionbox.agent.tool-timeout-seconds:600}")
    private int toolTimeoutSeconds;

    /**
     * 带超时执行工具。执行放到单独线程，并**在该线程里重新设置上下文**
     * （WorkspaceContext/SessionContext 是 ThreadLocal，不设的话相对路径解析会失效）。
     */
    private ToolResult runToolWithTimeout(ToolPlugin tool, Map<String, Object> args,
                                          String toolName, String sessionId) {
        String wsPath = sessionManager.getSession(sessionId)
            .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
            .map(ws -> ws.path())
            .orElse(null);
        java.util.concurrent.ExecutorService ex =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tool-" + toolName);
                t.setDaemon(true);
                return t;
            });
        try {
            var future = ex.submit(() -> {
                if (wsPath != null) {
                    com.lioncode.core.workspace.WorkspaceContext.set(wsPath);
                }
                com.lioncode.core.session.SessionContext.set(sessionId);
                try {
                    return tool.execute(args);
                } finally {
                    com.lioncode.core.workspace.WorkspaceContext.clear();
                    com.lioncode.core.session.SessionContext.clear();
                }
            });
            return future.get(toolTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("工具 {} 超过 {} 秒没返回，判定卡住并放弃等待 - 会话: {}",
                toolName, toolTimeoutSeconds, sessionId);
            return ToolResult.error("工具执行超时（超过 " + toolTimeoutSeconds + " 秒还没返回）: " + toolName
                + "。多半是在等网络、凭据或用户输入。请换个参数重试，或用别的等价工具完成这件事。");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("工具 {} 执行异常: {}", toolName, cause.toString());
            return ToolResult.error("工具执行异常: " + cause.getMessage());
        } finally {
            ex.shutdownNow();
        }
    }

    /**
     * 一轮里最多保留 MAX_TOOLS_PER_ROUND 个工具调用，多余的丢掉并告诉模型。
     *
     * 以前这里是硬性"一轮只准一个工具"（1.1.4 时代模型一次吐好几个调用、参数还错，
     * 只能一个个来）。但本机解码 11-12 token/s，一轮一个工具 = 50 个工具 50 轮 ≈ 7 分钟。
     * 现在原生通道已通（服务端按 Qwen 模板解析 &lt;function=…&gt;），一轮 3 个互不依赖的
     * 调用完全没问题；执行仍严格按顺序、逐个回结果，所以有依赖的任务不受影响。
     */
    private List<ChatMessage.ToolCall> capToolsPerRound(String sessionId,
                                                        List<ChatMessage.ToolCall> toolCalls) {
        if (toolCalls.size() <= MAX_TOOLS_PER_ROUND) {
            return toolCalls;
        }
        int extra = toolCalls.size() - MAX_TOOLS_PER_ROUND;
        log.warn("模型一次返回 {} 个工具调用，本轮只执行前 {} 个（剩 {} 个请下一轮再给）- 会话: {}",
            toolCalls.size(), MAX_TOOLS_PER_ROUND, extra, sessionId);
        eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
            Map.of("droppedCount", extra, "limit", MAX_TOOLS_PER_ROUND),
            "一轮工具数限制：已忽略多余工具调用 " + extra + " 个");
        return List.copyOf(toolCalls.subList(0, MAX_TOOLS_PER_ROUND));
    }

    /**
     * 一轮最多生成多少 token —— 本地模型必须封顶。
     *
     * 实测 llama-server 起来时带的是 `-n 4096`，而解码只有 11-12 token/s：
     * 模型要是话多，一轮就能写 4096 个 token ≈ **6 分 20 秒**（日志里真出现过
     * `eval time = 380323.93 ms / 4096 tokens`）。用户看到的就是"卡住了"。
     *
     * 本地封 1024（约 90 秒上限，正常一轮只要 40-60 个 token，够用）；
     * 云端不封，交给服务端默认。
     */
    private Integer maxTokensPerRound(String sessionId) {
        if (configStore == null) {
            return null;
        }
        if (configStore.isLocalMode()) {
            return roundCapBySession.computeIfAbsent(sessionId, k -> LOCAL_MAX_TOKENS_PER_ROUND);
        }
        // 自定义 API 指到本机服务（127.0.0.1/localhost）也一样慢，一并封顶
        Object url = configStore.snapshot().get("baseUrl");
        if (url instanceof String s) {
            String lower = s.toLowerCase();
            if (lower.contains("127.0.0.1") || lower.contains("localhost") || lower.contains("0.0.0.0")) {
                return roundCapBySession.computeIfAbsent(sessionId, k -> LOCAL_MAX_TOKENS_PER_ROUND);
            }
        }
        return null;
    }

    /**
     * 撞到生成长度上限（finish_reason=length）：本轮多半被截断了（tool_call 写了一半）。
     * 下一轮把上限翻倍，让它能把"写大文件"这种本来就长的调用写完；顶层封 MAX_TOKENS_CEILING。
     * 用户下一条消息会复位（见 toolGuard.reset 那两处调用旁边）。
     */
    private void bumpRoundCap(String sessionId) {
        int now = roundCapBySession.getOrDefault(sessionId, LOCAL_MAX_TOKENS_PER_ROUND);
        int next = Math.min(now * 2, MAX_TOKENS_CEILING);
        if (next != now) {
            roundCapBySession.put(sessionId, next);
            log.warn("生成长度撞顶（finish_reason=length），本轮上限 {} → {} - 会话: {}", now, next, sessionId);
        }
    }

    /**
     * 按工具自己声明的 JSON Schema，把参数值转成正确的类型。
     *
     * <p>【为什么非要在这里做】模型给的是"文本"：文本通道（本地盒子默认）下
     * {@code <parameter=lines>5</parameter>} 解析出来是字符串 "5"，
     * 原生通道也可能给 {@code "5"}。而工具里的写法是
     * {@code ((Number) arguments.get("lines")).intValue()} —— 直接
     * {@code class java.lang.String cannot be cast to class java.lang.Number}。
     * 实测（用户装 1.1.8 后跑"把工具都调一遍"）：
     * <pre>
     *   glob_files     ❌ 匹配失败: class java.lang.String cannot be cast to class java.lang.Number
     *   head_tail_file ❌ 读取失败: 同上
     *   directory_tree ❌ 生成目录树失败: 同上
     * </pre>
     * 12 个工具文件都这么取参数（modify_file 的 startLine/endLine 同理）。
     *
     * <p>只按 schema 里声明的类型转，不瞎猜：integer→Long、number→Double、
     * boolean→Boolean、array/object→先当 JSON 解析、string→数字也转成字符串。
     * 转不了就原样留着，让工具自己报错，这里不抛异常。
     */
    private Map<String, Object> coerceArguments(ToolPlugin tool, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty() || tool == null) {
            return arguments;
        }
        Object defObj = tool.getFunctionDefinition() == null
            ? null : tool.getFunctionDefinition().get("parameters");
        if (!(defObj instanceof Map<?, ?> def)) {
            return arguments;
        }
        Object propsObj = def.get("properties");
        if (!(propsObj instanceof Map<?, ?> props)) {
            return arguments;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(arguments);
        for (Map.Entry<?, ?> e : props.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!out.containsKey(key) || !(e.getValue() instanceof Map<?, ?> prop)) {
                continue;
            }
            String type = prop.get("type") == null ? "" : String.valueOf(prop.get("type"));
            Object value = out.get(key);
            try {
                if (value instanceof String s) {
                    String t = s.trim();
                    // 模型偶尔把值连引号一起写进来：\"5\" / "5"
                    if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
                        t = t.substring(1, t.length() - 1);
                    }
                    switch (type) {
                        case "integer" -> out.put(key, Long.valueOf(t));
                        case "number" -> out.put(key, Double.valueOf(t));
                        case "boolean" -> out.put(key, "true".equalsIgnoreCase(t) || "1".equals(t));
                        case "array", "object" -> {
                            if (t.startsWith("[") || t.startsWith("{")) {
                                out.put(key, objectMapper.readValue(t, Object.class));
                            }
                        }
                        default -> { /* string：原样 */ }
                    }
                } else if (value instanceof Number n && "string".equals(type)) {
                    out.put(key, String.valueOf(n));
                }
            } catch (Exception ex) {
                log.debug("参数 {} 按 {} 转换失败，原样传给工具: {}", key, type, value);
            }
        }
        return out;
    }

    /**
     * 工具名写错时，挑几个最接近的现有工具名当提示（实测能省掉一整轮白跑）。
     *
     * <p>用户日志里模型调过 {@code delete_directory_placeholder}（并不存在），
     * 只回"未找到工具"它会换个猜法继续试；带上候选它基本一次就改对。
     */
    private String suggestToolNames(String wrong, AgentMode mode) {
        if (wrong == null || wrong.isBlank()) {
            return "";
        }
        String w = wrong.toLowerCase().replace('_', ' ').trim();
        String[] parts = w.split("\\s+");
        java.util.List<String> scored = new java.util.ArrayList<>();
        for (ToolPlugin t : pluginRegistry.getToolsByMode(mode)) {
            String name = t.getName() == null ? "" : t.getName().toLowerCase();
            if (name.isBlank()) {
                continue;
            }
            int score = 0;
            for (String p : parts) {
                if (p.length() >= 3 && name.contains(p)) {
                    score += 2;
                }
            }
            String flat = name.replace('_', ' ');
            if (flat.contains(w) || w.contains(flat)) {
                score += 3;
            }
            if (score > 0) {
                scored.add(score + ":" + t.getName());
            }
        }
        if (scored.isEmpty()) {
            return "。可用工具见系统提示词里的清单（不要自己造工具名）";
        }
        scored.sort(java.util.Comparator.comparingInt((String s) -> -Integer.parseInt(s.split(":")[0])));
        java.util.List<String> top = new java.util.ArrayList<>();
        for (String s : scored) {
            top.add(s.split(":", 2)[1]);
            if (top.size() == 3) {
                break;
            }
        }
        return "。你是不是想用这些之一：" + String.join("、", top) + "（别自己造工具名）";
    }

    /** 参数指纹：用来判断"是不是一模一样的调用"（喂给 ToolCallGuard） */
    private String argsFingerprint(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(new java.util.TreeMap<>(arguments));
        } catch (Exception e) {
            return String.valueOf(arguments);
        }
    }

    /**
     * 往会话里塞一条「系统提示」。
     *
     * <p>【注意用 user 角色而不是 system】Qwen 的 Jinja 模板（llama-server 默认启用 --jinja）
     * 遇到不在开头的 system 消息会直接 raise_exception：
     * "System message must be at the beginning."，服务端回 HTTP 500，
     * 用户看到的是"模型调用失败: HTTP 500"，任务当场断掉（2026-09-28 23:4x 真出现过）。
     * 所以中途的提示一律走 user 角色（内容自带【系统提示】前缀），
     * 同时 buildMessages() 里还有一道兜底：历史里的 system 消息不在开头也会降级成 user。
     */
    private void addNotice(String sessionId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String body = text.startsWith("【系统提示】") ? text : "【系统提示】" + text;
        conversationHistory.addMessage(ConversationMessage.user(sessionId, body));
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

        // 0. 最先试 Qwen 模板原生格式 <tool_call><function=名字><parameter=键>值</parameter>
        //
        // 【为什么必须排在最前面】我们的权重是 Qwen 模板，模型最习惯的输出就是这个形式
        // （llama-server 开 --jinja 时服务端本来会替我们解析成标准 tool_calls，我们没开）。
        // 实测漏了这条就直接失败：23:03 那次会话模型输出了完整的
        // <call><function=execute_command><parameter=command>ls -la && pwd</parameter></function></call>，
        // 下面的 JSON 解析器却把这个文本拿去当 JSON 解析，报
        // "Unexpected character ('<')"，整轮工具调用作废 —— 模型白写一轮，
        // 用户看到的是"它说要调用工具，然后什么都没发生"。
        for (QwenToolCallParser.Call parsed : QwenToolCallParser.parse(text)) {
            String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            toolCalls.add(new ChatMessage.ToolCall(id, parsed.name(), parsed.arguments()));
            log.info("解析到模板原生工具调用: {} {}", parsed.name(), parsed.arguments());
        }
        if (!toolCalls.isEmpty()) {
            return toolCalls;
        }

        // 1. 再尝试解析XML格式
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
        // 移除模板原生格式（可能没被 <tool_call> 包裹）
        result = QwenToolCallParser.stripCalls(result);
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
