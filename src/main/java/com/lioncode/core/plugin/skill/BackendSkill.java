package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

/**
 * 后端开发技能包（兼容壳）。
 *
 * <p>能力正文已经搬到 {@code skills/backend/SKILL.md}（随包发布，用户目录
 * {@code ~/.lioncode/skills/backend/SKILL.md} 里的同名技能会覆盖它）。
 * 这个类只负责把文件内容按老的 {@link SkillPlugin} 接口交出去 ——
 * 详见 {@link FileBackedSkill} 里"为什么留着老接口"的说明。</p>
 */
@Component
public class BackendSkill extends FileBackedSkill {

    public BackendSkill(SkillRepository repository) {
        super(repository);
    }

    @Override
    protected String skillId() {
        return "backend";
    }

    @Override
    protected String fallbackName() {
        return "后端开发技能";
    }

    @Override
    protected String fallbackDescription() {
        return "后端代码编写、调试、编译排错、接口设计、依赖处理等后端相关能力";
    }
}
