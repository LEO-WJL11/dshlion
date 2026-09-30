package com.lioncode.core.plugin;

import com.lioncode.core.agent.spi.AgentSpi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 大循环插件：控制"Agent 怎么派发工具调用"。
 *
 * <p>它把三个本来写死在 AgentLoop 里的兜底值变成用户可调的设置：
 * <ul>
 *   <li>{@code maxIterations} —— 一条用户消息最多允许几轮工具调用
 *       （AgentLoop 里原来的兜底是 200 轮，一轮最多 3 个调用）；</li>
 *   <li>{@code toolTimeoutSeconds} —— 单个工具最多跑多少秒，超了就当它卡住
 *       （原来固定 600 秒）；</li>
 *   <li>{@code silentRounds} —— 连续几轮"模型什么都没吐/没有工具调用"就按正常收尾
 *       （原来固定 2 次）。</li>
 * </ul>
 *
 * <p>【为什么用 SPI 而不是直接改 AgentLoop 的常量】这三个值本质是"策略"，
 * 而 AgentLoop 是核心循环。策略写在核心循环里，用户想调就只能改代码重新打包；
 * 走 SPI 之后：插件开 = 用设置里的值，插件关 = AgentLoop 走它自己的默认值，
 * 一条 if 就切换，而且不用动 AgentLoop 一行。</p>
 */
@Component
public class AgentLoopPlugin implements Plugin, AgentSpi {

    public static final String PLUGIN_ID = "plugin.agent-loop";

    private final PluginSettings settings;

    public AgentLoopPlugin(PluginSettings settings) {
        this.settings = settings;
    }

    /**
     * 挂到 Agent 主循环。
     *
     * <p>【为什么敢在 @PostConstruct 里注册】这里只往 AgentSpi 的静态表里放一个引用，
     * 不碰 EventStore / 注册表那些"要等别的 Bean 初始化完"的东西 ——
     * 那些必须等 ApplicationRunner（见 PluginBootstrap 的注释）。
     * 在这里注册的好处是：Web 端口对外服务之前它就已经在了，第一条消息就能用上设置。</p>
     */
    @PostConstruct
    public void install() {
        AgentSpi.register(this);
    }

    @PreDestroy
    public void uninstall() {
        AgentSpi.unregister(this);
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "agent_loop";
    }

    @Override
    public String getDisplayName() {
        return "Agent 大循环";
    }

    @Override
    public String getDescription() {
        return "控制 Agent 派发工具调用的方式：最大轮次、单个工具超时、连续空转容忍轮数";
    }

    @Override
    public PluginType getType() {
        return PluginType.SYSTEM;
    }

    @Override
    public PluginKind getKind() {
        return PluginKind.AGENT_LOOP;
    }

    @Override
    public String spiName() {
        return "Agent 大循环插件";
    }

    /**
     * 排在 100（默认值）之后一点没意义、之前也没必要 —— 用默认序。
     * 它只提供 loopOptions，不参与工具过滤，和别的 SPI 没有先后依赖。
     */
    @Override
    public int order() {
        return 100;
    }

    // ------------------------------------------------------------------
    // 设置读写（REST 与 AgentLoop 都走这里）
    // ------------------------------------------------------------------

    public int maxIterations() {
        int v = settings.intOf("loop", "maxIterations", PluginSettings.DEFAULT_MAX_ITERATIONS);
        return v > 0 ? v : PluginSettings.DEFAULT_MAX_ITERATIONS;
    }

    public int toolTimeoutSeconds() {
        int v = settings.intOf("loop", "toolTimeoutSeconds",
            PluginSettings.DEFAULT_TOOL_TIMEOUT_SECONDS);
        return v > 0 ? v : PluginSettings.DEFAULT_TOOL_TIMEOUT_SECONDS;
    }

    public int silentRounds() {
        int v = settings.intOf("loop", "silentRounds", PluginSettings.DEFAULT_SILENT_ROUNDS);
        return Math.max(v, 0);
    }

    /**
     * 把设置交给 AgentLoop。
     *
     * <p>插件被关掉时返回空表：AgentLoop 拿不到覆盖值，就用它自己的兜底常量
     * —— 也就是"插件关掉 = 回到出厂行为"，这是开关该有的语义。</p>
     */
    @Override
    public Map<String, Object> loopOptions(String sessionId) {
        if (!settings.isEnabled(this)) {
            return Map.of();
        }
        // 【只回"用户真的在设置里改过的"项】不能把默认值也一起回上去 ——
        // 回上去就等于**覆盖**了配置文件里的值，`--lionbox.agent.tool-timeout-seconds=20`
        // 这类启动参数会被插件的默认值（300 秒）悄悄顶掉。实测就出过这个事故：
        // 工具卡住测试把超时设成 20 秒，结果真卡了 301 秒才被放行（套件直接判失败）。
        // 语义定成：设置里没写 → 不吭声，让 AgentLoop 用配置/默认值；写了 → 按用户写的来。
        Map<String, Object> section = settings.section("loop");
        Map<String, Object> out = new LinkedHashMap<>();
        if (section.containsKey("maxIterations")) {
            out.put("maxIterations", maxIterations());
        }
        if (section.containsKey("toolTimeoutSeconds")) {
            out.put("toolTimeoutSeconds", toolTimeoutSeconds());
        }
        if (section.containsKey("silentRounds")) {
            out.put("silentRounds", silentRounds());
        }
        return out;
    }
}
