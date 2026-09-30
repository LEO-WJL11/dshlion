package com.lioncode.core.plugin.team;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.SessionContext;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子智能体工具 {@code agent_spawn}：把一件事**整包**交给一个独立的子 Agent 去干。
 *
 * <p>【和"再调几个工具"有什么不一样】主 Agent 的上下文里已经塞满了前面几十轮的工具结果，
 * 再做一件独立的事，那些无关历史全都跟着发一遍（本机 11 token/s，白烧时间），
 * 而且主 Agent 中途分心很容易把两件事搅在一起。子智能体开的是**全新会话**：
 * 干净上下文 + 只带这一件事的说明，干完只把结论带回来。这就是"派活"的价值。</p>
 *
 * <p>【约束是插件给的，不是这里写死的】递归层级上限、并发上限、用哪个模型，
 * 全部读 {@link SubAgentPlugin} 的设置（用户在"设置 → 插件 → 子智能体"里改）。
 * 超限时**不抛异常**，而是把一句人话当工具结果回给模型 —— 模型看到就能自己改做法
 * （比如"层级超了，我自己直接干"），整个任务不会因为一次越界就废掉。</p>
 */
public class SubAgentTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTool.class);

    /** 子 Agent 最终回答带回主 Agent 时的长度上限（结论不需要上万字） */
    private static final int MAX_ANSWER_CHARS = 6000;

    /**
     * 当前递归深度（ThreadLocal）。
     *
     * <p>为什么用 ThreadLocal 而不是参数：子 Agent 是**同步**跑在派发它的那条线程上的
     * （{@code agentLoop.processMessage} 会一直跑到出结果），所以"这条线程现在在第几层"
     * 就是天然准确的层级。子 Agent 再派子智能体时读到的是同一份计数，层级自然累加。</p>
     */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 当前正在跑的子智能体数量（跨会话，防止同时派太多把本机模型挤爆）。 */
    private static final AtomicInteger RUNNING = new AtomicInteger();

    private final SubAgentPlugin plugin;
    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;

    public SubAgentTool(SubAgentPlugin plugin, AgentLoop agentLoop, SessionManager sessionManager) {
        this.plugin = plugin;
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getId() {
        return "tool.agent.spawn";
    }

    @Override
    public String getName() {
        return "agent_spawn";
    }

    @Override
    public String getDescription() {
        return "派一个子智能体独立完成一件完整的事，只把结论带回来（适合独立、边界清楚、"
            + "又不想污染当前上下文的活）。task 里要写清楚要什么结果、给哪些线索。";
    }

    @Override
    public com.lioncode.core.plugin.PluginKind getKind() {
        return com.lioncode.core.plugin.PluginKind.SUBAGENT;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public PermissionLevel getRequiredPermission() {
        // 子智能体可能去改文件，所以按"工作区写"要权限，不能算只读
        return PermissionLevel.WORKSPACE_WRITE;
    }

    @Override
    public boolean isAvailableInMode(AgentMode mode) {
        // 派活属于"进阶能力"：极简模式（只给文件 + shell）里不出现，
        // 免得模型在最简环境下也开始分兵。
        return mode != AgentMode.MINIMAL;
    }

    @Override
    protected Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task", Map.of("type", "string",
            "description", "交给子智能体的完整任务说明：要什么结果、已知线索、边界条件"));
        props.put("mode", Map.of("type", "string",
            "description", "子智能体的工作模式：standard（默认）或 minimal（只给文件+终端）"));
        props.put("model", Map.of("type", "string",
            "description", "可选：让子智能体用哪个模型（留空 = 用插件设置里的默认）"));
        return Map.of("type", "object", "properties", props, "required", java.util.List.of("task"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String task = getStringArg(arguments, "task", null);
        if (task == null || task.isBlank()) {
            return error("agent_spawn 需要 task 参数：把要子智能体做的事写清楚");
        }

        SubAgentConfig config = plugin.config();
        int childDepth = plugin.childDepth(DEPTH.get());

        // 1) 层级闸门：超了就回一句人话，不抛异常（模型会自己改成"我来干"）
        String blocked = plugin.checkSpawn(childDepth);
        if (blocked != null) {
            return error(blocked);
        }

        // 2) 并发闸门：先占坑再判断，判断不过立刻还回去
        int running = RUNNING.incrementAndGet();
        try {
            String tooMany = plugin.checkConcurrency(running);
            if (tooMany != null) {
                return error(tooMany);
            }

            String parentSessionId = SessionContext.get();
            String workspaceId = parentSessionId == null ? null
                : sessionManager.getSession(parentSessionId).map(SessionManager.Session::workspaceId).orElse(null);
            if (workspaceId == null) {
                return error("派子智能体失败：当前会话没有绑定工作区（子智能体需要一个能干活的工作区）");
            }

            AgentMode childMode = parseMode(getStringArg(arguments, "mode", "standard"));
            String model = getStringArg(arguments, "model", null);
            if (model == null || model.isBlank()) {
                model = config.model();
            }

            SessionManager.Session child = sessionManager.createSession(workspaceId, childMode);
            sessionManager.renameSession(child.sessionId(), "子智能体：" + firstLine(task, 24));

            String prompt = "你是被主 Agent 派来做一件具体事情的子智能体，做完就把结论说清楚。\n"
                + "【任务】\n" + task + "\n\n"
                + "要求：动手时用工具，别只给建议；做完用一段话给结论（做了什么、结果是什么、"
                + "有什么没做完）。不要反问，除非缺的信息确实无法从工作区推断。";

            int previousDepth = DEPTH.get();
            DEPTH.set(childDepth);
            long t0 = System.currentTimeMillis();
            try {
                String answer = agentLoop.processMessage(child.sessionId(), prompt, childMode,
                    ThinkingLevel.LOW, model);
                long cost = System.currentTimeMillis() - t0;
                log.info("子智能体完成：层级 {}，模式 {}，耗时 {} ms，会话 {}",
                    childDepth, childMode.getCode(), cost, child.sessionId());
                String body = answer == null || answer.isBlank() ? "（子智能体没有给出结论）" : answer;
                if (body.length() > MAX_ANSWER_CHARS) {
                    body = body.substring(0, MAX_ANSWER_CHARS) + "\n…（结论过长已截断）";
                }
                return success("【子智能体结论】（层级 " + childDepth + "，模式 " + childMode.getCode()
                    + "，耗时 " + (cost / 1000) + " 秒，会话 " + child.sessionId() + "）\n" + body);
            } finally {
                DEPTH.set(previousDepth);
            }
        } catch (Exception e) {
            // 子智能体炸了不能把主任务带崩：回一句错误让模型自己决定是重试还是自己干
            log.warn("子智能体执行异常: {}", e.toString());
            return error("子智能体执行异常：" + e.getMessage() + "（可以自己直接做，或换个说法再派一次）");
        } finally {
            RUNNING.decrementAndGet();
        }
    }

    private static AgentMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentMode.STANDARD;
        }
        String s = raw.trim().toLowerCase();
        if (s.contains("min") || s.contains("简")) {
            return AgentMode.MINIMAL;
        }
        return AgentMode.STANDARD;
    }

    private static String firstLine(String s, int max) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /** 当前这条线程的递归层级（测试用）。 */
    static int currentDepth() {
        return DEPTH.get();
    }
}
