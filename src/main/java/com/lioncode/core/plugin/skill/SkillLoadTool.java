package com.lioncode.core.plugin.skill;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code skill_load} 工具：按 id 取回某个技能的完整指令。
 *
 * <p>【为什么要有这个工具】系统提示词里只放"技能目录"（一行一个：id、名字、一句话、
 * 什么时候用），不放正文 —— 正文全塞进去，每条消息都要先花上千 token 读一遍技能说明书，
 * 本地模型 11 token/s，这就是实打实的一分钟。目录让**模型自己判断**要不要用某个技能，
 * 要用了再调它取正文，只在这一轮付费。</p>
 *
 * <p>【为什么极简模式也开放】技能目录在两个模式下都会注入提示词；目录里写了
 * "先调 skill_load"，工具却不在极简模式的清单里，模型一调就是一次 ❌ 白跑一轮。
 * 这个工具本身只读内存里的技能文件，零副作用，放行没有风险。</p>
 */
@Component
public class SkillLoadTool extends AbstractToolPlugin {

    private final SkillRepository repository;

    public SkillLoadTool(SkillRepository repository) {
        this.repository = repository;
    }

    @Override
    public String getId() {
        return "tool.skill.load";
    }

    @Override
    public String getName() {
        return "skill_load";
    }

    @Override
    public String getDescription() {
        return "加载某个技能的完整指令（技能 id 见系统提示词的技能目录）；按技能做事之前先调它";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public PermissionLevel getRequiredPermission() {
        return PermissionLevel.READ_ONLY;
    }

    @Override
    public boolean isAvailableInMode(AgentMode mode) {
        // 见类注释：目录两个模式都注入，工具就得两个模式都在
        return true;
    }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "name", Map.of("type", "string", "description", "技能 id，例如 backend")
            ),
            "required", new String[]{"name"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String name;
        try {
            name = getRequiredStringArg(arguments, "name");
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        // 不存在 / 解析坏了 / 被用户禁用 —— 三种情况给三种能照着改的提示，
        // 不要一律回"技能不存在"（实测模型会反复换名字重试，白烧好几轮）
        String reason = repository.unusableReason(name);
        if (reason != null) {
            return error(reason);
        }
        SkillDefinition def = repository.findEnabled(name).orElseThrow();
        StringBuilder sb = new StringBuilder();
        sb.append("技能 ").append(def.id()).append("（").append(def.displayName()).append("）的完整指令，");
        sb.append("请按下面这套做法完成当前任务：\n\n");
        sb.append(def.body().trim());
        if (!def.tools().isEmpty()) {
            sb.append("\n\n（这个技能通常要用到的工具：").append(String.join("、", def.tools())).append("）");
        }
        return success(sb.toString());
    }
}
