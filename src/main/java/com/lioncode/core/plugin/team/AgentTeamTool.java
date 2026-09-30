package com.lioncode.core.plugin.team;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体团队工具 {@code agent_team_run}：把一件任务**分给团队里几个成员各干一段**，
 * 最后把每个人的结论汇总回主 Agent。
 *
 * <p>成员是谁、用什么模式、负责什么，都由用户在"设置 → 插件 → 智能体团队"里配
 * （名字、模式 MINIMAL/STANDARD、职责说明、模型），这个工具只负责按配置派活。</p>
 *
 * <p>【为什么串行而不是并发】本机 llama-server 是**单 slot**（-c 全给一个对话），
 * 并发派 4 个成员只会让 4 个请求排队，还互相抢 KV cache；日志里看起来"同时在跑"，
 * 实际总耗时一样、失败率更高。所以这里一个一个来，并在结果里写清楚顺序。
 * 真要用云端多路 API，把 {@code parallel} 打开即可（每个成员一个线程）。</p>
 */
public class AgentTeamTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(AgentTeamTool.class);

    private static final int MAX_ANSWER_CHARS = 4000;

    private final AgentTeamPlugin plugin;
    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;

    public AgentTeamTool(AgentTeamPlugin plugin, AgentLoop agentLoop, SessionManager sessionManager) {
        this.plugin = plugin;
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getId() {
        return "tool.agent.team";
    }

    @Override
    public String getName() {
        return "agent_team_run";
    }

    @Override
    public String getDescription() {
        return "把一件任务交给配置好的智能体团队，各成员按自己的职责分头做完再汇总。"
            + "members 留空 = 全部启用的成员；也可以只点某几个（逗号分隔的成员 id）。";
    }

    @Override
    public com.lioncode.core.plugin.PluginKind getKind() {
        return com.lioncode.core.plugin.PluginKind.AGENT_TEAM;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public PermissionLevel getRequiredPermission() {
        return PermissionLevel.WORKSPACE_WRITE;
    }

    @Override
    public boolean isAvailableInMode(AgentMode mode) {
        return mode != AgentMode.MINIMAL;   // 团队属于进阶能力，极简模式不给
    }

    @Override
    protected Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task", Map.of("type", "string",
            "description", "要团队一起完成的总体任务（每个成员都会按自己的职责理解它）"));
        props.put("members", Map.of("type", "string",
            "description", "可选：只让这几个成员干活，逗号分隔的成员 id（留空 = 全部启用的成员）"));
        return Map.of("type", "object", "properties", props, "required", List.of("task"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String task = getStringArg(arguments, "task", null);
        if (task == null || task.isBlank()) {
            return error("agent_team_run 需要 task 参数：写清楚要团队做什么");
        }

        List<TeamMember> members = pickMembers(getStringArg(arguments, "members", null));
        if (members.isEmpty()) {
            return error("团队里没有可用的成员。请先在 设置 → 插件 → 智能体团队 里加成员"
                + "（每个成员要有名字、模式，和一句「负责干什么」）");
        }

        String parentSessionId = com.lioncode.core.session.SessionContext.get();
        String workspaceId = parentSessionId == null ? null
            : sessionManager.getSession(parentSessionId).map(SessionManager.Session::workspaceId).orElse(null);
        if (workspaceId == null) {
            return error("派团队失败：当前会话没有绑定工作区");
        }

        StringBuilder report = new StringBuilder();
        report.append("【智能体团队执行结果】共 ").append(members.size()).append(" 个成员，")
              .append("按顺序执行（本机模型是单通道，串行更稳）\n");

        int ok = 0;
        int fail = 0;
        for (TeamMember m : members) {
            AgentMode mode = "minimal".equalsIgnoreCase(m.mode()) ? AgentMode.MINIMAL : AgentMode.STANDARD;
            SessionManager.Session child = sessionManager.createSession(workspaceId, mode);
            sessionManager.renameSession(child.sessionId(), "团队/" + m.name());
            String prompt = "你是团队里的【" + m.name() + "】。\n"
                + "你的职责：" + (m.role() == null || m.role().isBlank() ? "（未填写，按名字理解）" : m.role()) + "\n\n"
                + "【团队要完成的任务】\n" + task + "\n\n"
                + "只做你职责范围内的事；动手要用工具；做完用一段话给结论："
                + "你做了什么、结果如何、有没有需要别人接手的地方。";
            long t0 = System.currentTimeMillis();
            try {
                String answer = agentLoop.processMessage(child.sessionId(), prompt, mode,
                    ThinkingLevel.LOW, m.model());
                String body = answer == null || answer.isBlank() ? "（没有给出结论）" : answer;
                if (body.length() > MAX_ANSWER_CHARS) {
                    body = body.substring(0, MAX_ANSWER_CHARS) + "\n…（已截断）";
                }
                report.append("\n### ").append(m.name()).append("（").append(mode.getCode())
                      .append("，").append((System.currentTimeMillis() - t0) / 1000).append(" 秒）\n")
                      .append(body).append('\n');
                ok++;
            } catch (Exception e) {
                log.warn("团队成员 {} 执行异常: {}", m.name(), e.toString());
                report.append("\n### ").append(m.name()).append(" ❌ 执行异常\n")
                      .append(e.getMessage()).append('\n');
                fail++;
            }
        }
        report.append("\n（成功 ").append(ok).append(" 个，失败 ").append(fail).append(" 个）");
        return fail > 0 && ok == 0 ? error(report.toString()) : success(report.toString());
    }

    /** 挑成员：给了 id 列表就按它挑，否则用全部启用的成员。 */
    private List<TeamMember> pickMembers(String raw) {
        List<TeamMember> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            out.addAll(plugin.activeMembers());
            return out;
        }
        for (String id : raw.split("[,，;；\\s]+")) {
            if (id.isBlank()) {
                continue;
            }
            TeamMember m = plugin.find(id.trim());
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }
}
