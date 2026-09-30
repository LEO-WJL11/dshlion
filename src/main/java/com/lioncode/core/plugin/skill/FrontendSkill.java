package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

/**
 * 前端开发技能包（兼容壳）。
 *
 * <p>能力正文在 {@code skills/frontend/SKILL.md}；用户目录里的同名技能优先。
 * 详见 {@link FileBackedSkill}。</p>
 */
@Component
public class FrontendSkill extends FileBackedSkill {

    public FrontendSkill(SkillRepository repository) {
        super(repository);
    }

    @Override
    protected String skillId() {
        return "frontend";
    }

    @Override
    protected String fallbackName() {
        return "前端开发技能";
    }

    @Override
    protected String fallbackDescription() {
        return "网页前端编写、样式调试、组件开发、接口对接等前端相关能力";
    }
}
