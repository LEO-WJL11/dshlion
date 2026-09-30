package com.lioncode.core.plugin;

import com.lioncode.core.plugin.automation.AutomationPlugin;
import com.lioncode.core.plugin.review.ApprovalReviewPlugin;
import com.lioncode.core.plugin.team.AgentTeamPlugin;
import com.lioncode.core.plugin.team.SubAgentPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把"系统插件"（终端、大循环、子智能体、智能体团队、自动授权审查、自动化任务）
 * 注册进 {@link PluginRegistry}。
 *
 * <p>【为什么用 ApplicationRunner 而不是 @PostConstruct】{@link PluginRegistry#register}
 * 会往 {@code EventStore} 里记一条 PLUGIN_LOADED 事件，而 EventStore 的存储目录是在它自己的
 * {@code @PostConstruct} 里才赋值的。如果我这边构造完就注册，事件持久化会撞上还没赋值的路径 ——
 * 一个 NPE 就能让整个应用起不来。ApplicationRunner 在所有单例都初始化完之后才跑，稳。
 * （工具/技能插件走的 {@code PluginAutoRegistration} 也是 ApplicationRunner，同一个道理。）</p>
 *
 * <p>{@code @Order(20)}：排在 {@code PluginAutoRegistration}（默认序，最低优先级）之后。
 * 其实两者互不依赖（注册顺序不影响任何行为），写上序号只是为了日志里"工具先、系统后"好读。</p>
 */
@Component
@Order(20)
public class PluginBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginBootstrap.class);

    private final PluginRegistry pluginRegistry;
    private final TerminalPlugin terminalPlugin;
    private final AgentLoopPlugin agentLoopPlugin;
    private final SubAgentPlugin subAgentPlugin;
    private final AgentTeamPlugin agentTeamPlugin;
    private final ApprovalReviewPlugin approvalReviewPlugin;
    private final AutomationPlugin automationPlugin;

    public PluginBootstrap(PluginRegistry pluginRegistry,
                           TerminalPlugin terminalPlugin,
                           AgentLoopPlugin agentLoopPlugin,
                           SubAgentPlugin subAgentPlugin,
                           AgentTeamPlugin agentTeamPlugin,
                           ApprovalReviewPlugin approvalReviewPlugin,
                           AutomationPlugin automationPlugin) {
        this.pluginRegistry = pluginRegistry;
        this.terminalPlugin = terminalPlugin;
        this.agentLoopPlugin = agentLoopPlugin;
        this.subAgentPlugin = subAgentPlugin;
        this.agentTeamPlugin = agentTeamPlugin;
        this.approvalReviewPlugin = approvalReviewPlugin;
        this.automationPlugin = automationPlugin;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 逐个注册、逐个兜异常：某一个系统插件出问题（比如设置文件里那一小段被手改坏了），
        // 不能让其余插件陪葬 —— 插件系统的第一原则是"坏一块不能全废"。
        List<Plugin> systemPlugins = List.of(
            terminalPlugin, agentLoopPlugin, subAgentPlugin,
            agentTeamPlugin, approvalReviewPlugin, automationPlugin);
        int ok = 0;
        for (Plugin p : systemPlugins) {
            try {
                pluginRegistry.register(p);
                ok++;
            } catch (Throwable t) {
                log.error("系统插件注册失败（其余插件继续）: {}", p.getId(), t);
            }
        }
        log.info("=== 系统插件注册完成 === {}/{} （终端 / 大循环 / 子智能体 / 智能体团队 / 授权审查 / 自动化任务）",
            ok, systemPlugins.size());
    }
}
