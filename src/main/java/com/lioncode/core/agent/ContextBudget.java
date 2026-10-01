package com.lioncode.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话的上下文窗口预算。
 *
 * <p>【为什么要这个东西】本机模型是 256K 上下文，可**每一条消息都要把整个前缀重新预填充一遍**
 * （prefill），窗口开多大，每一轮就要多算多少 —— 这是这台机器上最贵的一项开销。
 * 所以默认窗口收到 16K：够干绝大多数活，预填充也只有 256K 的十六分之一。
 * 真遇到大项目 16K 不够时，由 Agent 自己用 {@code context_window} 工具把窗口调大，
 * 干完再调回来（工具描述里写了这条约束，见 {@link com.lioncode.core.plugin.tool.context.ContextWindowTool}）。</p>
 *
 * <p>两层：全局默认（{@code lionbox.agent.context-limit-tokens}，默认 16384）+
 * 每会话覆盖（Agent 用工具改的是这一层）。覆盖只在内存里，进程重启就回到默认 ——
 * 这是有意的："临时为大项目开大窗口"本身就不该变成永久设置。</p>
 */
@Component
public class ContextBudget {

    private static final Logger log = LoggerFactory.getLogger(ContextBudget.class);

    /** 全局默认窗口（token）。0 = 不限，交给"模型窗口 × 75%"那条老路。 */
    private final int defaultLimit;

    /** 每会话覆盖：sessionId -> token 数（0 表示这个会话不限） */
    private final Map<String, Integer> perSession = new ConcurrentHashMap<>();

    public ContextBudget(@Value("${lionbox.agent.context-limit-tokens:16384}") int defaultLimit) {
        this.defaultLimit = Math.max(0, defaultLimit);
        log.info("上下文窗口默认预算: {} token{}", this.defaultLimit,
            this.defaultLimit == 0 ? "（0 = 用模型窗口的 75%）" : "");
    }

    /** 全局默认（REST/界面显示用） */
    public int defaultLimit() {
        return defaultLimit;
    }

    /**
     * 这个会话当前该用多大窗口。
     *
     * @return token 数；0 表示"不设限，用模型窗口 × 75%"
     */
    public int limitFor(String sessionId) {
        if (sessionId == null) {
            return defaultLimit;
        }
        Integer v = perSession.get(sessionId);
        return v == null ? defaultLimit : v;
    }

    /** 这个会话有没有被 Agent 手动调过 */
    public boolean isOverridden(String sessionId) {
        return sessionId != null && perSession.containsKey(sessionId);
    }

    /**
     * Agent 调窗口。
     *
     * @param tokens 新的窗口大小；0 = 不设限（用模型窗口的 75%）
     * @return 生效后的值
     */
    public int set(String sessionId, int tokens) {
        int v = Math.max(0, tokens);
        if (sessionId == null) {
            return v;
        }
        if (v == defaultLimit) {
            // 调回默认值就等于"没调过"，不留下覆盖记录
            perSession.remove(sessionId);
        } else {
            perSession.put(sessionId, v);
        }
        log.info("会话 {} 的上下文窗口改为 {} token", sessionId, v);
        return v;
    }

    /** 回到全局默认 */
    public int reset(String sessionId) {
        if (sessionId != null) {
            perSession.remove(sessionId);
        }
        return defaultLimit;
    }

    /** 会话结束/清空时把覆盖扔掉（sessionId 是单调的，不清也不会串，但清了更干净） */
    public void forget(String sessionId) {
        if (sessionId != null) {
            perSession.remove(sessionId);
        }
    }

    /** 快照（REST 返回给界面） */
    public Map<String, Object> snapshot(String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultLimit", defaultLimit);
        m.put("sessionLimit", limitFor(sessionId));
        m.put("overridden", isOverridden(sessionId));
        return m;
    }
}
