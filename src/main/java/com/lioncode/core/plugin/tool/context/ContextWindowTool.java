package com.lioncode.core.plugin.tool.context;

import com.lioncode.core.agent.ContextBudget;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让 Agent 自己调上下文窗口的工具。
 *
 * <p>【为什么要给模型这个权力】默认窗口 16K 是为了省预填充（本机模型 256K 全开，
 * 每轮都要把整个前缀重算一遍，这是最贵的开销）。但"够不够用"只有干活的模型自己知道：
 * 读一个几千行的文件、或者要同时盯好几个模块，16K 就是不够。以前只能让用户去设置里改，
 * 还得重启；现在模型自己调，而且**立刻生效**（下一次模型调用就用新窗口）。</p>
 *
 * <p>约束写在工具描述和系统提示里（这也是用户明确要求的"写段提示词约束它怎么用"）：
 * 默认就别动；确实不够才加，加完用完必须调回来。工具返回值里也会再提醒一次当前值。</p>
 */
@Component
public class ContextWindowTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowTool.class);

    /** 下限：再小连系统提示 + 工具定义都放不下，压缩会原地打转 */
    private static final int MIN_TOKENS = 4096;
    /** 上限：本机模型原生 256K，但不允许一步开到顶（留出 75% 的余量给"下一轮还要追加"） */
    private static final int MAX_TOKENS = 196_608;

    private final ContextBudget budget;

    public ContextWindowTool(ContextBudget budget) {
        this.budget = budget;
    }

    @Override
    public String getId() { return "tool.context.window"; }

    @Override
    public String getName() { return "context_window"; }

    @Override
    public String getDescription() {
        return "调整本会话的上下文窗口大小（token），立即生效。默认 16K。"
             + "只有在 16K 真的装不下（比如要通读大文件、跨多个模块改代码）时才调大；"
             + "任务做完必须调回 16384，否则每一轮都要多算预填充。"
             + "tokens=0 表示不设限（用模型窗口的 75%）。";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tokens", Map.of("type", "integer",
            "description", "新的窗口大小（token）。16384 = 默认；调大用于大项目；0 = 不设限"));
        props.put("reason", Map.of("type", "string",
            "description", "为什么需要调（一句话，会记进日志，方便事后看这笔开销值不值）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"tokens"});
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            int tokens = getIntArg(arguments, "tokens", -1);
            if (tokens < 0) {
                return error("tokens 必须是 0 或正整数（0 = 不设限）");
            }
            if (tokens != 0 && tokens < MIN_TOKENS) {
                return error("太小了：窗口至少 " + MIN_TOKENS + " token，"
                    + "再小连系统提示和工具定义都放不下，会陷入反复压缩。想省就用 16384。");
            }
            if (tokens > MAX_TOKENS) {
                return error("太大了：最多 " + MAX_TOKENS + " token（本机模型原生 256K，"
                    + "但要留出余量给下一轮追加的内容）");
            }
            String sessionId = SessionContext.get();
            String reason = getStringArg(arguments, "reason", "");
            int now = budget.set(sessionId, tokens);

            log.info("Agent 调整上下文窗口: session={} {} -> {} token（理由：{}）",
                sessionId, budget.defaultLimit(), now, reason.isBlank() ? "未说明" : reason);

            StringBuilder sb = new StringBuilder();
            sb.append("上下文窗口已改为 ").append(describe(now)).append("，下一次模型调用就会用新窗口。");
            if (now == 0) {
                sb.append("（0 = 不设限，按模型窗口的 75% 走）");
            }
            if (now > budget.defaultLimit() && now != 0) {
                sb.append("【别忘了】这件事做完就把窗口调回 ").append(budget.defaultLimit())
                  .append("（再调一次 context_window，tokens=").append(budget.defaultLimit())
                  .append("）—— 窗口越大，每一轮的预填充越贵。");
            }
            return success(sb.toString());
        } catch (Exception e) {
            return error("调窗口失败: " + e.getMessage());
        }
    }

    private static String describe(int tokens) {
        if (tokens == 0) {
            return "不设限";
        }
        if (tokens % 1024 == 0) {
            return (tokens / 1024) + "K（" + tokens + " token）";
        }
        return tokens + " token";
    }
}
