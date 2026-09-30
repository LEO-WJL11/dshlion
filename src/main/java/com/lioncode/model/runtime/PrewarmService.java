package com.lioncode.model.runtime;

import com.lioncode.core.agent.AgentLoop;
import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.session.SessionManager;
import com.lioncode.core.workspace.WorkspaceManager;
import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ChatMessage;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.adapter.ModelResponse;
import com.lioncode.model.config.AppConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 开机预热：把系统提示词 + 工具定义的前缀缓存提前算好。
 *
 * <h3>为什么需要它</h3>
 * 预填充（prompt processing）是算力受限的，而我们的请求里「系统提示词 + 54 个工具的
 * 定义」有 8-9K token。在纯 CPU 机器上（22 核 AVX2，约 20-30 tok/s 预填充速度），
 * 冷启动第一条消息要等好几分钟才吐出第一个字 —— 这是本地版最大的体验风险。
 *
 * 但 llama-server 会把已处理的 token 留在 slot 的 KV cache 里，下次请求只要
 * **前缀相同**，就只处理新增的那一段。于是把这段开销挪到用户提问之前：
 *
 * <pre>
 *   本机启动 → 加载模型 → 发一次「系统提示词 + 一句废话」（max_tokens=1）
 *   → 前缀缓存填好了
 *   用户提问 → 只预填充自己那句话（几十个 token）→ 秒回
 * </pre>
 *
 * <h3>前缀必须一致</h3>
 * 系统提示词只取决于「工作区路径 + 模式 + 技能匹配」：
 *   - 技能块按用户消息匹配，消息为空串时通常不命中 → 与真实请求一致
 *   - 所以预热取「最近一次会话的工作区 + 模式」，同一工作区内的提问都能命中
 *   - 换工作区后第一条消息会重新预填充一次（可接受，之后又命中）
 *
 * <h3>顺带产出性能数据</h3>
 * 预热请求会回报 prompt_tokens 与耗时，据此算出真实的**预填充速度**。
 * 这个数字之前只能靠估算，现在每次预热都能实测到，日志里会打出来。
 */
@Component
public class PrewarmService {

    private static final Logger log = LoggerFactory.getLogger(PrewarmService.class);

    /** 预热请求里的占位用户消息，正常回答不会被用到（只要 1 个 token） */
    private static final String DUMMY_USER_MESSAGE = "预热";

    private final AgentLoop agentLoop;
    private final AdapterManager adapterManager;
    private final SessionManager sessionManager;
    private final WorkspaceManager workspaceManager;
    private final AppConfigStore configStore;
    private final LocalModelRuntime localRuntime;

    /**
     * 部署级默认值（application.yml）。
     * 服务端镜像会把 on-start 设成 true；客户端保持 false，免得用户一开机就占几个 G 内存。
     * 用户还可以在 app-config.json 里覆盖（界面开关），两边都读。
     */
    @org.springframework.beans.factory.annotation.Value("${lionbox.prewarm.on-start:false}")
    private boolean onStartDefault;

    @org.springframework.beans.factory.annotation.Value("${lionbox.prewarm.enabled:true}")
    private boolean enabledDefault;

    @org.springframework.beans.factory.annotation.Value("${lionbox.prewarm.force:false}")
    private boolean forceDefault;

    /** 最近一次预热的结果（给界面显示用） */
    private volatile Map<String, Object> lastResult = Map.of("warmed", false, "reason", "尚未预热");

    public PrewarmService(AgentLoop agentLoop, AdapterManager adapterManager,
                          SessionManager sessionManager, WorkspaceManager workspaceManager,
                          AppConfigStore configStore, LocalModelRuntime localRuntime) {
        this.agentLoop = agentLoop;
        this.adapterManager = adapterManager;
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
        this.configStore = configStore;
        this.localRuntime = localRuntime;
    }

    /** 用户配置优先，没有就用部署默认值 */
    private boolean cfg(String key, boolean fallback) {
        Object v = configStore.get(key, null);
        return v instanceof Boolean b ? b : fallback;
    }

    /**
     * 应用启动后按配置决定要不要预热。
     *
     * 客户端（模型跑在用户自己电脑上）默认**不预热** —— 用户可能只想看看界面、
     * 或者打算用自己的 API，没必要一开机就占几个 G 内存。
     * 本地版把 lionbox.prewarm.on-start 打开，开机即热。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onStartup() {
        if (!cfg("warmupOnStart", onStartDefault)) {
            return;
        }
        log.info("配置要求开机预热，开始…");
        new Thread(() -> warmUp("开机预热"), "lionbox-prewarm").start();
    }

    /**
     * 最近一次预热的结果
     */
    public Map<String, Object> lastResult() {
        return lastResult;
    }

    /**
     * 执行预热。
     *
     * @param reason 触发原因，只用于日志与界面显示
     * @return 结果：warmed / skipped 原因 / promptTokens / seconds / tokensPerSecond
     */
    public Map<String, Object> warmUp(String reason) {
        long t0 = System.currentTimeMillis();
        try {
            String skip = whySkip();
            if (skip != null) {
                lastResult = Map.of("warmed", false, "reason", skip, "trigger", reason);
                log.info("跳过预热（{}）：{}", reason, skip);
                return lastResult;
            }

            ModelAdapter adapter = adapterManager.getActiveAdapter();
            if (adapter == null) {
                lastResult = Map.of("warmed", false, "reason", "没有可用的模型适配器", "trigger", reason);
                return lastResult;
            }

            // 自带端点：先把模型拉起来（惰性加载时它可能还没加载）
            // ensureRunning() 会阻塞到模型就绪，所以整个预热跑在独立线程里
            String baseUrl = String.valueOf(configStore.get("baseUrl", ""));
            if (localRuntime.manages(baseUrl) && !localRuntime.ensureRunning()) {
                LocalModelRuntime.Status st = localRuntime.status();
                lastResult = Map.of("warmed", false,
                    "reason", "本地模型未就绪: " + (st.lastError() == null ? "未知" : st.lastError()),
                    "trigger", reason);
                log.warn("预热中止：{}", lastResult.get("reason"));
                return lastResult;
            }

            // 取最近一次会话的模式与工作区：同一工作区内的提问都能命中这份前缀
            AgentMode mode = AgentMode.STANDARD;
            String workspacePath = null;
            for (SessionManager.Session s : sessionManager.getAllSessions()) {
                String p = workspaceManager.getWorkspace(s.workspaceId())
                    .map(ws -> ws.path()).orElse(null);
                if (p != null && !p.isBlank()) {
                    mode = sessionManager.getEffectiveMode(s.sessionId());
                    workspacePath = p;
                    break;
                }
            }
            if (workspacePath == null) {
                workspacePath = System.getProperty("user.dir");
            }

            AgentLoop.WarmupPlan plan = agentLoop.buildWarmupPlan(mode, workspacePath);

            String model = configStore.get("model", "");
            long t1 = System.currentTimeMillis();
            ModelResponse resp = adapter.chatWithOptions(plan.messages(), model,
                ThinkingLevel.MEDIUM, plan.toolDefinitions(), null, 1);
            long cost = System.currentTimeMillis() - t1;

            int promptTokens = resp != null && resp.usage() != null ? resp.usage().promptTokens() : 0;
            double seconds = cost / 1000.0;
            double tps = seconds > 0 ? promptTokens / seconds : 0;

            lastResult = Map.of(
                "warmed", true,
                "trigger", reason,
                "mode", mode.getCode(),
                "workspace", workspacePath,
                "toolDefinitions", plan.toolDefinitions().size(),
                "promptTokens", promptTokens,
                "millis", cost,
                "seconds", Math.round(seconds * 100) / 100.0,
                "tokensPerSecond", Math.round(tps * 10) / 10.0);

            log.info("预热完成（{}）：前缀 {} token，耗时 {} 秒 → {} tok/s 预填充速度",
                reason, promptTokens, String.format("%.2f", seconds), String.format("%.1f", tps));
            log.info("预热总耗时 {} 秒（含模型加载）", (System.currentTimeMillis() - t0) / 1000.0);
            return lastResult;
        } catch (Exception e) {
            log.warn("预热失败（{}）：{}", reason, e.getMessage());
            lastResult = Map.of("warmed", false,
                "reason", "预热失败: " + e.getMessage(), "trigger", reason);
            return lastResult;
        }
    }

    /** 该不该跳过 */
    private String whySkip() {
        if (!cfg("warmupEnabled", enabledDefault)) {
            return "预热功能已关闭";
        }
        if (isBuiltinEndpoint() || cfg("warmupForce", forceDefault)) {
            return null;
        }
        // 用户自己填的云端 API：预热既没用（缓存不在我们手上）又要花钱，不做
        return "当前是自己填的 API 端点，不做预热（避免白花 token 费用）";
    }

    /** 当前是否指向自带端点（本机），而不是用户自填的云端 API */
    private boolean isBuiltinEndpoint() {
        if ("local".equals(configStore.get("providerMode", "local"))) {
            return true;
        }
        Object provider = configStore.get("provider", "");
        if (provider instanceof String s && ("lionbox-local".equals(s) || "lionbox-box".equals(s))) {
            return true;
        }
        String baseUrl = String.valueOf(configStore.get("baseUrl", "")).toLowerCase();
        return baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost");
    }
}
