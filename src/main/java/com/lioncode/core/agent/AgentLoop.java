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
     *   auto   → 默认都走原生：云端 OpenAI 兼容 API 和随软件拉起的 llama-server 都支持。
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
        // 【本地模型运行时走文本通道】—— 2026-09-29 抓包实测的结论，别凭印象改。
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

        // 0. 插件扩展点：用户消息改写（@ 文件 / @ 历史对话 展开成真实上下文）。
        //    事件里记的是**用户原话**（界面上要显示用户输入的样子，展开后的几 KB 文件内容
        //    塞进事件流会把界面刷爆），对话历史里存的是**展开后**的内容（模型要看到上下文）。
        String rawUserMessage = userMessage;
        userMessage = com.lioncode.core.agent.spi.AgentSpi.applyTransforms(sessionId, userMessage);

        // 1. 记录用户消息事件
        eventStore.recordEvent(sessionId, LionEvent.EventType.USER_MESSAGE,
            Map.of("content", rawUserMessage), "用户消息: " + truncate(rawUserMessage, 100));

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
        List<Map<String, Object>> toolDefinitions = nativeTools ? buildToolDefinitions(mode, sessionId) : List.of();
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

            // 4.4 大循环插件给的轮次上限（默认 0 = 不限，保持"只有用户能停"的老行为）。
            //     实测真会跑飞：压测里 web_search 那条任务模型连续 12 次 fetch_url 抓
            //     nodejs.org，没人拦就一直烧下去。用户要"能自己设上限"就给上限，
            //     不设就维持原样，不替用户做决定。
            int maxRounds = com.lioncode.core.agent.spi.AgentSpi
                .loopInt(sessionId, "maxIterations", 0);
            if (maxRounds > 0 && round > maxRounds) {
                String capMsg = "⏹ 已达到本轮最大工具调用轮数（" + maxRounds
                    + "，可在 设置 → 插件 → Agent 大循环 里调整）。";
                log.info(capMsg + " 会话: {}", sessionId);
                eventStore.recordEvent(sessionId, LionEvent.EventType.SYSTEM_ERROR,
                    Map.of("error", "轮次上限", "rounds", round - 1), capMsg);
                soundNotifier.play(SoundNotifier.Kind.ERROR);
                conversationHistory.addMessage(ConversationMessage.assistant(sessionId, capMsg));
                return capMsg;
            }

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

            // 4.5 上下文预算：历史 + 本轮到目前的工具结果快把模型窗口塞满时，先折叠成摘要。
            //     不做这件事的后果不是"变慢"，是**整个会话废掉**：请求超长 → 服务端 400 →
            //     用户看到"模型调用失败"，而且之后每条消息都还是超长。
            maybeCompressContext(sessionId, messages, model);

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

            // 6.5 一轮执行几个工具：默认**不限制**（模型给几个执行几个 —— 用户明确要求过，
            // 本机 11 token/s，砍成一轮一个等于把 50 个工具拖成 7 分钟）。
            // 但"Agent 大循环插件"把它做成可配：用户在 设置 → 插件 → Agent 大循环 里设了
            // 上限就按上限截断 —— 截断在第 9 步按**原始顺序**处理（超出的那条回一句
            // "未执行、下一轮继续"），这样 tool 结果的顺序永远和模型给的调用顺序一致。
            int declaredCount = toolCalls.size();
            int toolsCap = com.lioncode.core.agent.spi.AgentSpi
                .loopInt(sessionId, "maxToolsPerRound", 0);
            if (toolsCap > 0 && declaredCount > toolsCap) {
                log.info("模型一次返回 {} 个工具调用，按用户设置的上限 {} 执行 - 会话: {}",
                    declaredCount, toolsCap, sessionId);
                eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_START,
                    Map.of("declared", declaredCount, "cap", toolsCap),
                    "按用户设置截断本轮工具调用：" + declaredCount + " → " + toolsCap);
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

            // 8.5 超出上限的调用，在这里（assistant 消息之后）补一条"未执行"的结果。
            // 不补的话模型看到"我发了 5 个、只回来 2 个"，要么以为干完了、要么怀疑工具坏了。
            // 顺序按原始调用顺序走，和 tool_calls 一一对应。
            int executed = 0;
            int skippedByCap = 0;

            // 9. 执行工具调用（流式：识别到就执行；执行前检查暂停/停止）
            for (ChatMessage.ToolCall toolCall : toolCalls) {
                if (toolsCap > 0 && executed >= toolsCap) {
                    conversationHistory.addMessage(ConversationMessage.toolResult(
                        sessionId, toolCall.id(), toolCall.name(),
                        "未执行：本轮工具调用数超过用户设置的上限（设置 → 插件 → Agent 大循环 → "
                            + "一轮最多几个工具调用）。这一步没做，请在这一轮重新给出这个调用。"));
                    skippedByCap++;
                    continue;
                }
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
                    // 【用户要求】这里原来会"重复调用就终止任务""连续失败就跳过不执行"，
                    // 还会往对话里插【系统提示】。现在这些都没有了：
                    // 要不要继续试由模型判断，停不停由用户按界面上的 ⏹ 决定。
                    toolGuard.beforeCall(sessionId, toolCall.name(),
                        argsFingerprint(toolCall.arguments()));   // 只登记，不拦
                    toolGuard.afterCall(sessionId, toolCall.name(),
                        executeTool(sessionId, toolCall, mode));
                    executed++;
                }
            }
            if (skippedByCap > 0) {
                log.info("本轮有 {} 个工具调用按上限推迟到下一轮 - 会话: {}", skippedByCap, sessionId);
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
                                // 【用户要求】不再限制一轮几个工具调用：模型给几个就执行几个

                                // 有工具调用：保存助手消息，执行工具，然后继续下一轮
                                // 界面自己会按事件画"🔧 调用工具：X …"那一行，别再往正文里塞杂音文本
                                
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
                                        // 同非流式：不拦、不跳过、不终止、不插系统提示
                                        toolGuard.beforeCall(sessionId, toolCall.name(),
                                            argsFingerprint(toolCall.arguments()));
                                        toolGuard.afterCall(sessionId, toolCall.name(),
                                            executeTool(sessionId, toolCall, mode));
                                        // 发送工具执行结果通知
                                        sink.next(AgentChunk.toolCall(toolCall.name(), "执行完成"));
                                    }
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

        // 【用户要求】不再自动停止：以前超过 MAX_TOOL_ROUNDS 轮就"⏹ 已停止"，
        // 现在让任务一直跑下去 —— 想停就按界面上的 ⏹（那是用户的决定，不是我们的）。
        if (currentRound % 50 == 0) {
            log.info("工具轮次已到 {} 轮（不自动停止）会话: {}", currentRound, sessionId);
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
                        // 不限一轮几个调用（用户要求：别再丢模型给的东西）
                        // 继续执行工具
                        // 界面自己会按事件画"🔧 调用工具：X …"那一行，别再往正文里塞杂音文本
                        
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

        // 查找工具插件（用循环而不是 stream：下面 toolName 会被归一化改名，
        // 一旦重新赋值，方法里所有 lambda 捕获 toolName 的地方都会编译失败）
        ToolPlugin found = null;
        for (ToolPlugin t : pluginRegistry.getToolPlugins()) {
            if (t.getName().equals(toolName) || t.getId().equals(toolName)) {
                found = t;
                break;
            }
        }

        // 找不到就试一次"工具名归一化"：量化模型很爱写 ls / cat / bash 这类通用叫法，
        // 直接判"未找到工具"等于白烧一轮推理（本机一轮十几秒）。
        // 只有精确查找失败时才归一化，绝不会把已经正确的调用改坏；拿不准就照旧报错。
        if (found == null) {
            String canonical = ToolNameAliases.resolve(toolName, pluginRegistry.getToolPlugins().stream()
                .filter(t -> t.isAvailableInMode(mode))
                .map(ToolPlugin::getName)
                .filter(n -> n != null && !n.isBlank())
                .toList());
            if (canonical != null) {
                for (ToolPlugin t : pluginRegistry.getToolPlugins()) {
                    if (t.getName().equals(canonical)) {
                        found = t;
                        break;
                    }
                }
                if (found != null) {
                    log.info("工具名归一化: {} → {}", toolName, canonical);
                    toolName = canonical;
                }
            }
        }
        Optional<ToolPlugin> toolOpt = Optional.ofNullable(found);

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

        // 自动授权审查插件：工具本身没被策略拦，但用户开了"叫另一个模型来审核"，
        // 这里就真的去问一次模型，它说不放行就不执行。
        // 【为什么是"回一句错误"而不是静默拒绝】模型要看到拒绝理由才知道该怎么改
        // （换个更安全的做法 / 先解释清楚），否则它只会一遍遍重试同一个调用。
        String deny = reviewToolCall(sessionId, tool, toolName, coerceArguments(tool, toolCall.arguments()));
        if (deny != null) {
            log.warn("自动授权审查拦截: {} —— {}", toolName, deny);
            eventStore.recordEvent(sessionId, LionEvent.EventType.TOOL_CALL_ERROR,
                Map.of("toolName", toolName, "error", deny, "review", "deny"),
                "自动授权审查拦截: " + toolName);
            conversationHistory.addMessage(
                ConversationMessage.toolResult(sessionId, toolCall.id(), toolName, "错误: " + deny));
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
            // 【派发前先把参数类型转对】文本通道（本地模式默认）下所有参数都是字符串，
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
        String systemPrompt = buildSystemPrompt(mode, workspacePath, userMessage, useNativeTools(), sessionId);
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
        return buildSystemPrompt(mode, workspacePath, userMessage, nativeTools, null);
    }

    /**
     * 构建系统提示词（带会话 id 的版本，插件 SPI 要用它按会话过滤工具 / 追加段落）。
     */
    private String buildSystemPrompt(AgentMode mode, String workspacePath, String userMessage,
                                     boolean nativeTools, String sessionId) {
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
            prompt.append("execute_command 是**一个持续运行的终端**（同一工作区共用一个会话）：");
            prompt.append("cd 切过的目录、设过的变量和函数都会留到下一次调用，不用每条命令都重新 cd。\n");
        }
        prompt.append("\n");

        // 根据模式添加专属提示词（每个模式独立撰写，行为规则各不相同）
        prompt.append(modeInstructions(mode));

        // 极简模式：把"真的可用的工具名"钉在提示词里。
        //
        // 【为什么非要写死一份】上面那段只说"仅开放文件工具和 Shell 工具"，而模型对
        // "文件工具"的理解是它自己训练里的那套名字（试过 list_files、file_read…）。
        // 实测极简模式跑一轮，它照样去调 web_search / timestamp —— 全是模式外的工具，
        // 用户看到的就是一屏 ❌。所以这里把筛选后的真实工具名直接列出来，不给它猜的空间。
        if (mode == AgentMode.MINIMAL) {
            List<String> minimalNames = filteredTools(AgentMode.MINIMAL, sessionId).stream()
                .map(ToolPlugin::getName)
                .filter(n -> n != null && !n.isBlank())
                .sorted()
                .toList();
            prompt.append("本模式实际可用的工具**只有下面这些**（其余工具在本模式不存在，不要调用）：\n");
            prompt.append(String.join("、", minimalNames)).append("\n\n");
        }

        // 注入适用技能的领域能力提示词（按用户消息匹配）
        //
        // 【极简模式不注入】技能正文里经常出现"用 web_search 查一下""用 git_commit 提交"
        // 这类话，而极简模式只开放文件 + shell 工具 —— 注进去等于**教模型去调不存在的工具**，
        // 用户看到的就是一屏 ❌（实测套件就是这么抓到的：极简提示词里冒出 web_search）。
        if (mode != AgentMode.MINIMAL) {
            prompt.append(buildSkillPrompt(userMessage));
        }

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
        List<ToolPlugin> tools = filteredTools(mode, sessionId);
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
                          .append(promptDescription(tool)).append("\n");
                }
                prompt.append("\n");
                prompt.append(toolChoiceTable(tools)).append("\n");

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

        // 插件 SPI：追加段落（技能目录让模型自己挑技能、插件清单、团队智能体说明等）。
        // 放在最后：这些是"可选能力"，不能挤掉前面的硬性格式约定（模型只看前几屏）。
        for (String section : com.lioncode.core.agent.spi.AgentSpi
                .collectSections(sessionId, workspacePath, userMessage)) {
            prompt.append("\n").append(section).append("\n");
        }

        return prompt.toString();
    }

    /**
     * 清单里这条工具该怎么描述。
     *
     * <p>【为什么要人工写一份覆盖】原来直接用工具自己的 description 砍到 48 字，
     * 结果模型**选错工具**：问"config.txt 前 5 行是什么"，它调 list_directory；
     * 问"统计多少行代码"，它还是调 list_directory —— 因为清单里
     * {@code head_tail_file: 查看文件头部或尾部N行} 和 {@code line_count: 统计文件行数}
     * 这两句话没告诉它"用户这么说的时候该用我"。工具自己的描述是给"已经决定要用它的人"看的，
     * 清单要的是**选择依据**：触发词 + 和谁容易混。
     *
     * <p>没写覆盖的工具继续用原描述，不会漏。
     */
    private static String promptDescription(ToolPlugin tool) {
        String hint = TOOL_PROMPT_HINTS.get(tool.getName());
        return hint != null ? hint : shortDescription(tool.getDescription());
    }

    /** 高频工具的"选择依据"。写的时候只回答两个问题：用户会怎么说？别跟谁混？ */
    private static final Map<String, String> TOOL_PROMPT_HINTS = Map.ofEntries(
        Map.entry("read_file", "读文件内容（已经知道是哪个文件时用它）"),
        Map.entry("head_tail_file", "看文件开头/结尾 N 行。用户说“前 5 行/最后几行”就用它，**不要用 list_directory、不要用 read_file**"),
        Map.entry("line_count", "统计行数。用户问“有多少行代码/一共多少行”就用它；path 给目录会递归累计所有文件"),
        Map.entry("word_count", "统计行数、字数、字节数。用户问“多少字/多大”用它"),
        Map.entry("list_directory", "列目录下的文件和子目录。只在用户问“有哪些文件/列一下目录”时用"),
        Map.entry("directory_tree", "画目录树。用户说“目录结构/画给我看/树状”用它"),
        Map.entry("glob_files", "按名字或后缀找文件。用户说“所有 .java 文件/找找 xyz 文件”用它，pattern 传 **/*.java 这种"),
        Map.entry("search_in_files", "在**文件内容**里搜文本或正则。用户说“哪里提到了 TODO/搜一下内容”用它"),
        Map.entry("create_file", "只创建**空**文件。要写内容请用 write_file"),
        Map.entry("write_file", "新建文件并写入内容（也用于整体覆盖）。用户说“建个文件，写上…”用它"),
        Map.entry("append_file", "往文件末尾追加内容。用户说“追加一行/加到末尾”用它"),
        Map.entry("modify_file", "改文件内容：替换/插入/删除行。用 operation 指定动作，替换给 oldText+content，按行改给 startLine/endLine+content"),
        Map.entry("move_file", "移动或改名。source 是原路径、target 是新路径"),
        Map.entry("copy_file", "复制文件或目录。source → target"),
        Map.entry("delete_file", "删除文件或空目录。用户说“删掉/清理”用它"),
        Map.entry("create_directory", "创建目录（含父目录）"),
        Map.entry("file_info", "看文件大小、修改时间等信息"),
        Map.entry("execute_command", "在常驻终端里执行命令。用户说“跑一下/执行/编译/安装/装依赖”用它"),
        Map.entry("run_background", "后台运行长时间命令（服务、监听、常驻进程）"),
        Map.entry("stop_background", "停掉后台进程"),
        Map.entry("system_info", "系统信息（CPU、内存、操作系统）。用户问“什么配置/多少内存”用它"),
        Map.entry("timestamp", "当前时间。用户问“现在几点/今天几号”用它"),
        Map.entry("hash", "计算哈希值（md5/sha1/sha256）"),
        Map.entry("base64", "Base64 编码或解码"),
        Map.entry("json_format", "JSON 格式化、校验、压缩"),
        Map.entry("yaml_process", "YAML 格式化或校验"),
        Map.entry("generate_uuid", "生成 UUID"),
        Map.entry("get_env", "读环境变量"),
        Map.entry("dns_lookup", "域名解析成 IP"),
        Map.entry("fetch_url", "抓取网页正文（给定 URL 时用它）"),
        Map.entry("http_get", "发 HTTP GET 请求（要接口原始响应时用它）"),
        Map.entry("http_post", "发 HTTP POST 请求"),
        Map.entry("web_search", "联网搜索（不知道具体网址、要查资料时用它）"),
        Map.entry("download_file", "把 URL 上的文件下载到本地"),
        Map.entry("translate", "翻译文本"),
        Map.entry("working_directory", "查看或切换当前工作目录"),
        Map.entry("git_status", "查看 Git 仓库状态"),
        Map.entry("git_commit", "暂存并提交改动"),
        Map.entry("git_log", "查看提交历史"),
        Map.entry("git_diff", "查看改动差异"),
        Map.entry("git_branch", "查看、创建、切换分支"),
        Map.entry("git_init", "初始化 Git 仓库"),
        Map.entry("git_remote", "查看或管理远程仓库"),
        Map.entry("git_stash", "Git stash 保存/恢复/列出"),
        Map.entry("git_reset", "撤销暂存或回退提交"),
        Map.entry("ask_user", "需要用户做选择或补充信息时提问"),
        Map.entry("regex_test", "测试正则表达式匹配"),
        Map.entry("string_utils", "字符串处理：大小写、trim、长度"),
        Map.entry("escape_string", "字符串转义/反转义（html/json/java/url/regex/shell）"),
        Map.entry("number_convert", "进制转换"),
        Map.entry("format_code", "代码格式化（缩进、换行）"),
        Map.entry("markdown_render", "Markdown 转 HTML"),
        Map.entry("diff_text", "比较两段文本的差异"),
        Map.entry("change_permissions", "修改文件权限（可执行/可写/可读）"),
        Map.entry("cron_parse", "解析 Cron 表达式")
    );

    /** 从对照表的一行里认工具名（只认形如 xxx_yyy 的小写标识符）。 */
    private static final java.util.regex.Pattern TOOL_TOKEN =
        java.util.regex.Pattern.compile("\\b([a-z][a-z0-9_]{2,})\\b");

    /**
     * 生成工具选择对照表，并**把手头没有的工具那几行去掉**。
     *
     * <p>对照表是静态文案，里面点名了 head_tail_file、line_count 这些工具；
     * 用户在设置里把插件关掉之后，清单里已经没这个工具了，表格却还让模型去用它 ——
     * 模型照做就撞"未找到工具"，白烧一轮（本机一轮十几秒）。所以这里按当前实际可用的
     * 工具名过滤一遍：一行里"→"后面那个主工具不在名单里，整行删掉。
     */
    private static String toolChoiceTable(List<ToolPlugin> available) {
        java.util.Set<String> names = available.stream()
            .map(ToolPlugin::getName)
            .filter(n -> n != null && !n.isBlank())
            .collect(java.util.stream.Collectors.toSet());
        StringBuilder sb = new StringBuilder();
        for (String line : TOOL_CHOICE_TABLE.split("\n")) {
            int arrow = line.indexOf('→');
            String primary = null;
            if (arrow >= 0) {
                java.util.regex.Matcher m = TOOL_TOKEN.matcher(line.substring(arrow));
                if (m.find()) {
                    primary = m.group(1);
                }
            }
            if (primary != null && !names.contains(primary)) {
                continue;   // 这个工具当前不可用，别让模型去调
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 最容易混的几组工具，直接写成对照表。
     *
     * <p>55 个工具的清单里，模型要在两三个近义工具之间选；写完 48 字描述它还是会选错
     * （实测"前 5 行"和"多少行代码"都选了 list_directory）。对照表把"用户会怎么问 → 用哪个"
     * 挑明，比任何描述都直接。
     */
    private static final String TOOL_CHOICE_TABLE = """
        ## 别选错工具（下面这几组最容易混，逐条对照）
        **总原则：用户已经指明是哪个文件/目录时，直接用针对它的那个工具，不要先 list_directory 逛一圈。**
        - “文件的前 N 行 / 最后几行” → head_tail_file（不是 read_file，更不是 list_directory）
        - “有多少行 / 统计行数 / 多少行代码”（文件或目录都算）→ line_count
        - “有哪些文件 / 列一下目录” → list_directory；“目录结构 / 画成树” → directory_tree
        - “找文件（按名字、后缀）” → glob_files；“找内容（哪里提到 X）” → search_in_files
        - “建个文件并写上内容” → write_file；“只建一个空文件” → create_file
        - “追加到末尾” → append_file；“替换/改内容/按行改” → modify_file
        - “把 A 改名成 B / 移到某处” → move_file；“复制一份” → copy_file；“删掉” → delete_file
        - “跑命令 / 编译 / 安装 / 执行一次” → execute_command
        - “要一直跑的服务 / 每 5 秒做一次 / 常驻进程” → run_background（不要写脚本再手动跑）
        - “现在几点 / 今天几号” → timestamp；“什么 CPU、多少内存” → system_info
        - “算哈希” → hash；“base64” → base64；“JSON 格式化” → json_format
        - “查资料 / 网上搜” → web_search；“抓某个网址” → fetch_url
        - “把某段话翻译成英文/中文” → translate（不要自己翻译，用工具）
        - “算一下/转换/解析”这类纯计算，先看有没有对应工具，有就用，别自己心算
        """;

    /**
     * 按模式取工具，再过一遍插件 SPI 的过滤（用户在设置里关掉的插件，它的工具直接从
     * 提示词里消失 —— 这样模型压根不会去调，比"调了再拒绝"省一整轮）。
     */
    private List<ToolPlugin> filteredTools(AgentMode mode, String sessionId) {
        List<ToolPlugin> all = pluginRegistry.getToolsByMode(mode);
        if (com.lioncode.core.agent.spi.AgentSpi.size() == 0) {
            return all;   // 没装插件：一个 if 就返回，零开销
        }
        java.util.Set<String> names = com.lioncode.core.agent.spi.AgentSpi.applyToolFilter(
            sessionId, all.stream().map(ToolPlugin::getName).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return all.stream().filter(t -> names.contains(t.getName())).toList();
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
     * 上下文预算上限（token）。
     *
     * <p>0 = 自动：问一下适配器这个模型的窗口有多大，取 75% 当预算（留 25% 给回答和
     * 下一轮的工具结果）。问不到就退化成 {@link #FALLBACK_CONTEXT_TOKENS}。
     * 非要手动钉死就用 {@code --lionbox.agent.context-limit-tokens=8000}。
     */
    @org.springframework.beans.factory.annotation.Value("${lionbox.agent.context-limit-tokens:0}")
    private int contextLimitTokens;

    /** 压缩时尾部保留多少条消息（越靠近现在越有用）。 */
    @org.springframework.beans.factory.annotation.Value("${lionbox.agent.context-keep-recent:16}")
    private int contextKeepRecent;

    /** 问不到模型窗口时的兜底预算：32K 对绝大多数本地/云模型都安全。 */
    private static final int FALLBACK_CONTEXT_TOKENS = 32768;

    /** 模型窗口查询结果缓存（别每轮都去问一遍适配器）。 */
    private volatile int cachedModelContextTokens = -1;

    /** 本次实际生效的上下文预算（token）。 */
    private int effectiveContextLimit(String model) {
        if (contextLimitTokens > 0) {
            return contextLimitTokens;
        }
        int ctx = cachedModelContextTokens;
        if (ctx <= 0) {
            ctx = FALLBACK_CONTEXT_TOKENS;
            try {
                ModelAdapter adapter = adapterManager.getActiveAdapter();
                if (adapter != null) {
                    for (var info : adapter.getAvailableModels()) {
                        if (info != null && info.maxContextTokens() != null && info.maxContextTokens() > 0
                                && (model == null || model.isBlank() || model.equals(info.id())
                                    || model.equals(info.name()))) {
                            ctx = info.maxContextTokens();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("问模型上下文窗口失败，用兜底 {} token: {}", FALLBACK_CONTEXT_TOKENS, e.toString());
            }
            cachedModelContextTokens = ctx;
        }
        // 只用到窗口的 75%：回答本身、以及下一轮追加的工具结果都要占地方
        return Math.max(2048, (int) (ctx * 0.75));
    }

    /**
     * 超预算就把中间那段历史折叠成摘要（就地替换 messages 的内容）。
     *
     * <p>只在**真的要发请求之前**做，压缩结果只影响这一次请求，不动落盘的历史 ——
     * 用户切换会话回来还能看到完整原文，这一点很重要（摘要只是给模型的"记忆副本"）。
     */
    private void maybeCompressContext(String sessionId, List<ChatMessage> messages, String model) {
        try {
            int limit = effectiveContextLimit(model);
            int keep = Math.max(4, contextKeepRecent);
            ContextCompressor.Result r = ContextCompressor.fit(messages, limit, keep);
            if (!r.compressed()) {
                return;
            }
            log.info("上下文压缩：{} → {} token，折叠 {} 条历史（预算 {} token）- 会话 {}",
                r.tokensBefore(), r.tokensAfter(), r.droppedMessages(), limit, sessionId);
            eventStore.recordEvent(sessionId, LionEvent.EventType.CONTEXT_COMPRESSED,
                Map.of("tokensBefore", r.tokensBefore(), "tokensAfter", r.tokensAfter(),
                       "dropped", r.droppedMessages(), "limit", limit),
                "上下文压缩：" + r.tokensBefore() + " → " + r.tokensAfter() + " token，折叠 "
                    + r.droppedMessages() + " 条历史");
            messages.clear();
            messages.addAll(r.messages());
        } catch (Exception e) {
            // 压缩是"保命"机制，它自己出问题绝不能把正常对话带崩
            log.warn("上下文压缩失败，按原样发送（可能超长）: {}", e.toString());
        }
    }

    /**
     * 按"提供商名字"挑适配器（自动授权审查、以后别的插件都用得上）。
     *
     * <p>认法故意宽松：用户可能填 {@code anthropic} / {@code messages} / {@code claude}，
     * 也可能填 {@code openai-compatible} / {@code openai} / {@code local}。认不出来、
     * 或者那个适配器当前不可用，就退回当前激活的适配器 —— 审查器找不到模型时
     * 宁可"用主模型审一次"，也不能让正常流程报错。</p>
     */
    private ModelAdapter pickAdapterFor(String provider) {
        if (provider == null || provider.isBlank()) {
            return adapterManager.getActiveAdapter();
        }
        String p = provider.trim().toLowerCase(java.util.Locale.ROOT);
        ModelAdapter.AdapterType type = null;
        if (p.contains("anthropic") || p.contains("messages") || p.contains("claude")) {
            type = ModelAdapter.AdapterType.ANTHROPIC;
        } else if (p.contains("openai") || p.contains("local") || p.contains("llama")
                || p.contains("compatible")) {
            type = ModelAdapter.AdapterType.OPENAI_COMPATIBLE;
        }
        if (type != null) {
            try {
                var opt = adapterManager.getAdapter(type);
                if (opt.isPresent() && adapterManager.isAdapterAvailable(type)) {
                    return opt.get();
                }
            } catch (Exception e) {
                log.debug("按提供商 {} 取适配器失败，退回当前适配器: {}", provider, e.toString());
            }
        }
        log.info("审查提供商 {} 没有对应的可用适配器，退回当前适配器", provider);
        return adapterManager.getActiveAdapter();
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
     * 本次派发实际用的工具超时：先问插件 SPI（"agent 大循环插件"允许用户在设置里改），
     * 插件没给就用配置文件里的值。做成方法而不是字段，是因为插件可以在运行时改设置 ——
     * 缓存成字段的话，用户改完得重启才生效。
     */
    private int timeoutFor(String sessionId) {
        int t = com.lioncode.core.agent.spi.AgentSpi.loopInt(sessionId, "toolTimeoutSeconds", toolTimeoutSeconds);
        return t > 0 ? t : toolTimeoutSeconds;
    }

    /**
     * 自动授权审查插件：让**另一个模型**看一眼这次工具调用要不要放行。
     *
     * <p>【和"审批策略"的分工】{@code approvalPolicy} 是本地规则（哪些工具一律禁、哪些要问用户），
     * 快、确定、不花钱；这个插件是"语义审核"——按工具名拦不住的场景（比如 `execute_command`
     * 里那串命令到底危不危险），本地规则看不懂，得让模型读一遍再判。两条互补，都要过。</p>
     *
     * <p>返回 null = 放行；返回非 null = 拒绝，字符串就是回给模型的理由。</p>
     *
     * <p>【失败必须放行】审核模型连不上、超时、返回没法解析 —— 一律当放行。
     * 反过来（连不上就拒绝）会让"网络一抖整台机器都不能干活"，那比漏审一次糟糕得多。</p>
     */
    private String reviewToolCall(String sessionId, ToolPlugin tool, String toolName,
                                  Map<String, Object> args) {
        try {
            var opt = pluginRegistry.getById(com.lioncode.core.plugin.review.ApprovalReviewPlugin.PLUGIN_ID);
            if (opt.isEmpty()
                    || !(opt.get() instanceof com.lioncode.core.plugin.review.ApprovalReviewPlugin review)) {
                return null;
            }
            var decision = review.check(sessionId, toolName, args);
            if (decision == null || !decision.needsReview()) {
                return null;
            }
            String prompt = review.buildReviewPrompt(toolName, args,
                sessionManager.getSession(sessionId)
                    .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
                    .map(ws -> ws.path())
                    .orElse("(未绑定工作区)"));
            String model = review.model() == null || review.model().isBlank() ? null : review.model();
            // 【"用哪个提供商"必须真的生效】之前这里只用了 model，provider 配了等于没配 ——
            // 用户明明填了"用另一个提供商来审"，实际还是拿主 Agent 的适配器去问，
            // 那就不是"叫另一个模型审核"了。这里按 provider 取对应适配器，取不到再退回当前适配器
            // （找不到就退回，绝不让审查把正常流程带崩）。
            ModelAdapter adapter = pickAdapterFor(review.provider());
            ModelResponse reply = adapter.chatWithOptions(
                List.of(ChatMessage.system("你是工具调用安全审核员，只回答 ALLOW 或 DENY，并给一句理由。"),
                        ChatMessage.user(prompt)),
                model, ThinkingLevel.LOW, List.of(), null, 200);
            var verdict = com.lioncode.core.plugin.review.ApprovalReviewPlugin
                .parseVerdict(reply == null ? null : reply.content());
            if (verdict != null && !verdict.allow()) {
                return review.denyMessage(toolName, verdict.reason());
            }
            return null;
        } catch (Exception e) {
            log.warn("自动授权审查出错，按放行处理（不能因为审核器坏了就不让干活）: {}", e.toString());
            return null;
        }
    }

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
            return future.get(timeoutFor(sessionId), java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("工具 {} 超过 {} 秒没返回，判定卡住并放弃等待 - 会话: {}",
                toolName, timeoutFor(sessionId), sessionId);
            return ToolResult.error("工具执行超时（超过 " + timeoutFor(sessionId) + " 秒还没返回）: " + toolName
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
     * 一轮里最多生成多少 token —— 本地模型必须封顶。
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
     * <p>【为什么非要在这里做】模型给的是"文本"：文本通道（本地模式默认）下
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

        // 0) 先把"键名写歪了"的参数改名到工具声明的名字上（file_path→path、max_depth→maxDepth、
        //    大小写不一致…）。只改键名、不动值，而且多个候选就放弃，所以不会有副作用。
        //    这一步在类型转换之前做，否则歪掉的键压根进不了下面的转换循环。
        for (Map.Entry<?, ?> e : props.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (out.containsKey(key)) {
                continue;
            }
            String matched = ToolArgAliases.matchKey(key, out.keySet());
            if (matched != null && out.get(matched) != null) {
                out.put(key, out.get(matched));
                log.debug("参数名归一化: {} → {}（工具 {}）", matched, key, tool.getName());
            }
        }

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
                1. 只使用文件工具（read_file / write_file / modify_file / create_file /
                   append_file / delete_file / move_file / copy_file / list_directory /
                   directory_tree / glob_files / search_in_files / line_count / word_count /
                   head_tail_file / file_info / change_permissions）和 Shell 工具
                   （execute_command / run_background / stop_background），不得调用其他任何工具。
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
        return buildToolDefinitions(mode, null);
    }

    /**
     * 构建工具定义列表（带会话，走插件过滤）。
     *
     * <p>【为什么要带 sessionId】原生通道下这份定义是随请求下发的，等于模型看到的完整工具集；
     * 如果这里不过滤，用户在设置里关掉的插件**照样会被下发给模型、而且真调用了还能执行** ——
     * "关掉插件"就变成了只影响文本通道的假开关。会话维度的开关必须两条通道都一致。
     */
    private List<Map<String, Object>> buildToolDefinitions(AgentMode mode, String sessionId) {
        List<ToolPlugin> tools = filteredTools(mode, sessionId);
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
