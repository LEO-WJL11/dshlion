package com.lioncode.core.plugin.automation;

import com.lioncode.core.agent.ThinkingLevel;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.PluginSettings;
import com.lioncode.core.session.SessionManager;
import com.lioncode.queue.SessionDispatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 自动化任务的"心脏"：定时看有没有到点的任务，到点就把它的 prompt 丢进对应会话。
 *
 * <p>【为什么是"往会话里丢一条消息"而不是别的做法】用户要的是"按设定时间/周期，
 * 在会话中自动执行任务"。会话本身就是这个软件的执行单元：丢一条消息进去，走的就是
 * 和用户手打一模一样的那条链路（队列 → AgentLoop → 工具 → 事件流），
 * 用户切回那个会话能看见完整的执行过程，也能随时点停止。另起一条"后台执行链路"
 * 只会造出一个用户看不见、也管不了的黑盒。</p>
 *
 * <p>【轮询而不改状态】{@link AutomationPlugin#pollDue(Instant)} 是**纯函数**（只看不写），
 * 判定完由这里调 {@link AutomationPlugin#markRun} 落"上次执行时间"。
 * 这样职责很清楚：插件管"什么时候该跑"，这里管"跑了就记账"。
 * 记账放在 submit 之后立刻做 —— 如果等到 future 完成再记，一轮长任务期间会被重复触发。</p>
 */
@Component
public class AutomationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunner.class);

    /** 轮询间隔（秒）。任务粒度最细是分钟级，15 秒足够准，也不会白烧 CPU。 */
    private static final int POLL_SECONDS = 15;

    private final PluginRegistry registry;
    private final PluginSettings settings;
    private final SessionManager sessionManager;
    private final SessionDispatcher dispatcher;

    private ScheduledExecutorService scheduler;

    public AutomationRunner(PluginRegistry registry, PluginSettings settings,
                            SessionManager sessionManager, SessionDispatcher dispatcher) {
        this.registry = registry;
        this.settings = settings;
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lionbox-automation");
            t.setDaemon(true);   // 守护线程：应用关闭时不会被它拖住
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("自动化任务轮询已启动（每 {} 秒检查一次）", POLL_SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 一次轮询：把到点的任务丢进会话。任何异常都吞掉，绝不把调度线程搞死。 */
    void tick() {
        try {
            AutomationPlugin plugin = automationPlugin();
            if (plugin == null) {
                return;
            }
            List<DueTask> due = plugin.pollDue(Instant.now());
            for (DueTask d : due) {
                String sessionId = d.sessionId();
                if (sessionId == null || sessionManager.getSession(sessionId).isEmpty()) {
                    // 会话被删了：不再执行，也不再记账，让用户自己看到任务"跑不动"
                    log.warn("自动化任务 {} 指向的会话 {} 不存在，跳过", d.task().id(), sessionId);
                    plugin.markRun(d.task().id(), Instant.now());
                    continue;
                }
                try {
                    dispatcher.submit(sessionId, d.prompt(), null, ThinkingLevel.MEDIUM, false);
                    plugin.markRun(d.task().id(), d.dueAt());
                    log.info("自动化任务已投递：{} → 会话 {}（{}）",
                        d.task().name(), sessionId, d.reason());
                } catch (Exception e) {
                    log.warn("自动化任务 {} 投递失败: {}", d.task().id(), e.toString());
                }
            }
        } catch (Exception e) {
            log.debug("自动化轮询异常（忽略）: {}", e.toString());
        }
    }

    /** 取自动化插件；被用户关掉了就什么都不做。 */
    private AutomationPlugin automationPlugin() {
        var opt = registry.getById(AutomationPlugin.PLUGIN_ID);
        if (opt.isEmpty() || !(opt.get() instanceof AutomationPlugin p)) {
            return null;
        }
        if (!settings.isEnabled(p)) {
            return null;
        }
        return p;
    }
}
