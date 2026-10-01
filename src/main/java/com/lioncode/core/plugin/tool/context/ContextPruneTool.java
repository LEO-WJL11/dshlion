package com.lioncode.core.plugin.tool.context;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让 Agent 自己裁剪上下文的工具。
 *
 * <p>【为什么需要】压缩（{@link com.lioncode.core.agent.ContextCompressor}）是"塞不下了自动折叠"，
 * 属于兜底；但很多时候模型自己清楚"前面那些探查过程已经没用了" ——
 * 比如已经定下方案，之前翻的十几个文件内容、跑歪的几次试错，全都可以扔掉。
 * 让它主动删，比等塞满再自动摘要更省、也更准。</p>
 *
 * <p>语义按用户的要求定："保留后面这几条，前面的删掉" —— 所以唯一的必填参数是
 * {@code keep_last}（保留最近几条），其余一律删。裁剪是**真的删**：从会话历史里移除并落盘，
 * 不是只影响这一次请求（用户明确说的是"让 AI 自己手动删掉"，那就得真删）。</p>
 *
 * <p>两个保护：① 不越过工具调用的配对边界（不会删掉 assistant(tool_calls) 却留下它的 tool 结果）；
 * ② 至少保留最近 2 条，且永远不动系统提示。删掉哪几条会在返回值里列出来，方便用户事后核对。</p>
 */
@Component
public class ContextPruneTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(ContextPruneTool.class);

    /** 最少保留几条：把最后两条（一般是"用户提问 + 模型回答"）删了就什么都记不住了 */
    private static final int MIN_KEEP = 2;

    private final ConversationHistory history;

    public ContextPruneTool(ConversationHistory history) {
        this.history = history;
    }

    @Override
    public String getId() { return "tool.context.prune"; }

    @Override
    public String getName() { return "context_prune"; }

    @Override
    public String getDescription() {
        return "裁剪本会话的上下文：保留最近 keep_last 条消息，把它之前的全部删掉（真删，落盘）。"
             + "用在「前面的探查已经没用了、但上下文快满」的时候：比如方案已定，"
             + "之前翻文件、试错的过程都可以扔。别拿它当压缩用 —— 塞不下时会自动压缩。"
             + "想先看看会删掉什么，用 dry_run=true。";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("keep_last", Map.of("type", "integer",
            "description", "保留最近几条消息（至少 " + MIN_KEEP + "），更早的全部删除"));
        props.put("reason", Map.of("type", "string",
            "description", "为什么这些可以删（一句话，记进日志）"));
        props.put("dry_run", Map.of("type", "boolean",
            "description", "true = 只报告会删掉什么，不真删（默认 false）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"keep_last"});
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            int keep = getIntArg(arguments, "keep_last", -1);
            if (keep < MIN_KEEP) {
                return error("keep_last 至少是 " + MIN_KEEP + "（把最后两条也删了就什么都不记得了）");
            }
            boolean dryRun = getBoolArg(arguments, "dry_run", false);
            String reason = getStringArg(arguments, "reason", "");
            String sessionId = SessionContext.get();
            if (sessionId == null || sessionId.isBlank()) {
                return error("拿不到当前会话 id，无法裁剪");
            }

            ConversationHistory.PruneResult r = history.pruneHistory(sessionId, keep, dryRun);
            if (r.total() == 0) {
                return success("这个会话还没有历史消息，没什么可裁剪的。");
            }
            if (r.removed() == 0) {
                return success("当前只有 " + r.total() + " 条消息，不需要裁剪"
                    + "（保留最近 " + keep + " 条时没有更早的可以删）。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append(dryRun ? "【试算】" : "").append("已裁剪 ").append(r.removed())
              .append(" 条早期消息，保留最近 ").append(r.kept()).append(" 条。\n");
            sb.append("删掉的是：\n");
            for (String line : r.preview()) {
                sb.append("  - ").append(line).append('\n');
            }
            if (!reason.isBlank()) {
                sb.append("（理由：").append(reason).append("）");
            }
            if (dryRun) {
                sb.append("\n要真的删掉，把 dry_run 去掉再调一次。");
            }
            log.info("Agent 裁剪上下文: session={} 删 {} 条 留 {} 条（理由：{}）",
                sessionId, r.removed(), r.kept(), reason.isBlank() ? "未说明" : reason);
            return success(sb.toString());
        } catch (Exception e) {
            return error("裁剪上下文失败: " + e.getMessage());
        }
    }
}
