package com.lioncode.core.plugin.skill;

import java.util.List;

/**
 * 兼容壳基类：把"读 SKILL.md 的技能仓库"包装成老的 {@link SkillPlugin} 接口。
 *
 * <p>【为什么留着 SkillPlugin 这套老接口】AgentLoop、PluginRegistry、PluginController
 * 都还按 SkillPlugin 找技能（{@code pluginRegistry.getSkillPlugins()}），
 * 直接删掉会把别人的代码编译不过。所以四个技能类保留成"壳"：
 * 内容全部搬进 {@code skills/<id>/SKILL.md}，Java 类只负责把文件里的东西读出来、
 * 用老接口的样子交出去。</p>
 *
 * <p>【文件坏了会怎样】技能文件缺失或解析失败时，这个壳退化成"名字还在、正文为空、
 * isApplicable 永远 false"，也就是这个技能静默失效 —— 而不是让应用起不来。
 * 坏在哪，{@code GET /api/skills} 的 error 字段里写着。</p>
 */
public abstract class FileBackedSkill implements SkillPlugin {

    private final SkillRepository repository;

    protected FileBackedSkill(SkillRepository repository) {
        this.repository = repository;
    }

    /** 技能 id，同时也是 {@code skills/<id>/SKILL.md} 的目录名。 */
    protected abstract String skillId();

    /** 技能文件读不到时用的名字（不能返回 null，插件列表会显示空白）。 */
    protected abstract String fallbackName();

    /** 技能文件读不到时用的说明。 */
    protected abstract String fallbackDescription();

    @Override
    public String getId() {
        // 插件 id 保持 "skill.xxx" 的老写法：日志、/api/plugins、老用户的印象里都是这个
        return "skill." + skillId();
    }

    /** 当前这份技能定义（可能不存在）。 */
    protected SkillDefinition definition() {
        return repository.find(skillId()).orElse(null);
    }

    private boolean ready() {
        SkillDefinition d = definition();
        return d != null && d.usable() && repository.isEnabled(d.id());
    }

    @Override
    public String getName() {
        SkillDefinition d = definition();
        return d != null && !d.displayName().isBlank() ? d.displayName() : fallbackName();
    }

    @Override
    public String getDescription() {
        SkillDefinition d = definition();
        return d != null && !d.description().isBlank() ? d.description() : fallbackDescription();
    }

    @Override
    public String getVersion() {
        SkillDefinition d = definition();
        return d != null && !d.version().isBlank() ? d.version() : "1.0.0";
    }

    @Override
    public List<String> getTags() {
        SkillDefinition d = definition();
        return d != null ? d.tags() : List.of();
    }

    @Override
    public String getSystemPromptFragment() {
        SkillDefinition d = definition();
        return d != null && d.usable() ? d.body() : "";
    }

    @Override
    public List<String> getRequiredToolIds() {
        SkillDefinition d = definition();
        return d != null ? d.requiredToolIds() : List.of();
    }

    @Override
    public List<String> getTaskTypeDescriptions() {
        SkillDefinition d = definition();
        return d != null ? d.taskTypes() : List.of();
    }

    @Override
    public boolean isApplicable(String userMessage) {
        // 坏掉的、被用户禁用的技能一律不匹配 —— 否则"禁用"这个开关等于没用
        // （AgentLoop 会照样把正文注进提示词）
        if (!ready()) {
            return false;
        }
        return definition().matchesKeywords(userMessage);
    }

    @Override
    public void initialize() {
        // 技能内容从文件读，构造时就已经在仓库里了，这里没有额外要初始化的
    }
}
