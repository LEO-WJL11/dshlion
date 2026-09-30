package com.lioncode.core.plugin.team;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 把"子智能体"和"智能体团队"两个工具挂进插件注册表。
 *
 * <p>【为什么不直接在工具类上打 @Component】这两个工具要注入 {@link AgentLoop}，
 * 而 AgentLoop 又依赖 PluginRegistry —— 工具如果也由 Spring 当普通 Bean 创建，
 * 很容易和"注册表 → 工具 → AgentLoop → 注册表"绕成一个环。这里由一个独立的
 * 注册器在启动完成后手动 new 出来再登记，依赖方向永远是单向的，也方便按插件开关
 * 决定要不要挂（用户关掉插件就不注册，模型连工具名都看不到）。</p>
 */
@Component
public class TeamToolRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamToolRegistrar.class);

    private final PluginRegistry registry;
    private final SubAgentPlugin subAgentPlugin;
    private final AgentTeamPlugin agentTeamPlugin;
    private final AgentLoop agentLoop;
    private final SessionManager sessionManager;

    public TeamToolRegistrar(PluginRegistry registry, SubAgentPlugin subAgentPlugin,
                             AgentTeamPlugin agentTeamPlugin, AgentLoop agentLoop,
                             SessionManager sessionManager) {
        this.registry = registry;
        this.subAgentPlugin = subAgentPlugin;
        this.agentTeamPlugin = agentTeamPlugin;
        this.agentLoop = agentLoop;
        this.sessionManager = sessionManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        register("tool.agent.spawn", new SubAgentTool(subAgentPlugin, agentLoop, sessionManager));
        register("tool.agent.team", new AgentTeamTool(agentTeamPlugin, agentLoop, sessionManager));
    }

    private void register(String id, com.lioncode.core.plugin.Plugin plugin) {
        if (registry.isRegistered(id)) {
            return;
        }
        try {
            registry.register(plugin);
            log.info("智能体团队工具已注册：{}（{}）", plugin.getName(), id);
        } catch (Exception e) {
            // 挂不上最多是少两个工具，绝不能让应用起不来
            log.warn("注册 {} 失败: {}", id, e.toString());
        }
    }
}
