package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

/**
 * 客户端开发技能包（兼容壳）。
 *
 * <p>能力正文在 {@code skills/client/SKILL.md}；用户目录里的同名技能优先。
 * 详见 {@link FileBackedSkill}。</p>
 */
@Component
public class ClientSkill extends FileBackedSkill {

    public ClientSkill(SkillRepository repository) {
        super(repository);
    }

    @Override
    protected String skillId() {
        return "client";
    }

    @Override
    protected String fallbackName() {
        return "客户端开发技能";
    }

    @Override
    protected String fallbackDescription() {
        return "桌面客户端程序开发相关能力，支持Electron、Qt、JavaFX等框架";
    }
}
