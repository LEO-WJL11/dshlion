package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

/**
 * 文档读写技能包（兼容壳）。
 *
 * <p>能力正文在 {@code skills/document/SKILL.md}；用户目录里的同名技能优先。
 * 详见 {@link FileBackedSkill}。</p>
 */
@Component
public class DocumentSkill extends FileBackedSkill {

    public DocumentSkill(SkillRepository repository) {
        super(repository);
    }

    @Override
    protected String skillId() {
        return "document";
    }

    @Override
    protected String fallbackName() {
        return "文档读写技能";
    }

    @Override
    protected String fallbackDescription() {
        return "专门负责撰写文档、阅读解析各类文档、文档整理、文档格式转换相关能力";
    }
}
