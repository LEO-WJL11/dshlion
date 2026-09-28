package com.lioncode.core.plugin.tool.system;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import com.lioncode.core.question.UserQuestionService;
import com.lioncode.core.session.SessionContext;
import com.lioncode.core.sound.SoundNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问的工具
 *
 * 什么时候该用：
 *   信息确实不够、而且**问一句比猜一句强**的时候。比如
 *   "要删的到底是哪个文件"、"生产库还是测试库"、"这个函数要保留向后兼容吗"。
 *   参数不全、需求含糊、要动不可逆的东西——先问，别硬猜。
 *
 * 什么时候别用：
 *   能从工作区里查到的（先 read_file / search_in_files 自己找）；
 *   用户刚说过的（别再问一遍）；纯寒暄。
 *
 * 调用后模型所在线程会**阻塞等用户回答**（默认最多 5 分钟），
 * 用户在界面上的输入框里回答，答案作为工具结果返回给模型继续做。
 * 超时会明确返回"用户没回答"，让模型自己收尾，不会死等。
 */
@Component
public class AskUserTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    /** 最多等多久（秒） */
    private static final int MAX_TIMEOUT = 300;

    private final UserQuestionService questionService;
    private final SoundNotifier soundNotifier;

    public AskUserTool(UserQuestionService questionService, SoundNotifier soundNotifier) {
        this.questionService = questionService;
        this.soundNotifier = soundNotifier;
    }

    @Override
    public String getId() { return "tool.system.ask_user"; }

    @Override
    public String getName() { return "ask_user"; }

    @Override
    public String getDescription() {
        return "向用户提问并等待回答。当信息不足、需求含糊或要执行不可逆操作时先问清楚，"
             + "不要靠猜。能自己从工作区查到的信息不要问。";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "question", Map.of(
                    "type", "string",
                    "description", "要问用户的问题，尽量具体、一次问清一件事"),
                "options", Map.of(
                    "type", "array",
                    "description", "可选项（可选）。给了的话界面上会显示成按钮，用户点一下就行；"
                                 + "不给就是自由输入",
                    "items", Map.of("type", "string")),
                "timeoutSeconds", Map.of(
                    "type", "integer",
                    "description", "最多等多少秒（可选，默认 300）")
            ),
            "required", new String[]{"question"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String question;
        try {
            question = getRequiredStringArg(arguments, "question");
        } catch (IllegalArgumentException e) {
            return error("缺少必填参数：question（要问用户的问题）");
        }

        String sessionId = SessionContext.get();
        if (sessionId == null || sessionId.isBlank()) {
            // 理论上不会发生：AgentLoop 执行工具前一定会设置会话上下文
            return error("当前不在会话上下文里，无法向用户提问");
        }

        List<String> options = readOptions(arguments);
        int timeout = readTimeout(arguments);

        // 提问音效：让用户不用盯着屏幕也知道"它在问我"
        soundNotifier.play(SoundNotifier.Kind.QUESTION);

        UserQuestionService.Result r = questionService.ask(sessionId, question, options, timeout);
        return switch (r.status()) {
            case ANSWERED -> success("用户回答：" + r.answer());
            case TIMEOUT -> success(r.text());
            case CANCELLED -> success(r.text());
        };
    }

    /** 读 options：既支持数组，也兼容前端/模型传过来的逗号分隔字符串 */
    private List<String> readOptions(Map<String, Object> arguments) {
        Object raw = arguments.get("options");
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，、|]")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out.size() > 8 ? out.subList(0, 8) : out;    // 选项太多了界面也没法看
    }

    /** 读 timeoutSeconds，夹到合理范围 */
    private int readTimeout(Map<String, Object> arguments) {
        Object raw = arguments.get("timeoutSeconds");
        int t = UserQuestionService.DEFAULT_TIMEOUT_SECONDS;
        if (raw instanceof Number n) {
            t = n.intValue();
        } else if (raw instanceof String s) {
            try {
                t = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 传了乱七八糟的东西就用默认值
            }
        }
        if (t <= 0) {
            t = UserQuestionService.DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(t, MAX_TIMEOUT);
    }
}
