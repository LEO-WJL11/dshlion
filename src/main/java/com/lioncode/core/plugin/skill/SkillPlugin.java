package com.lioncode.core.plugin.skill;

import com.lioncode.core.plugin.Plugin;

import java.util.List;

/**
 * 技能插件接口
 * 
 * Skill技能包作为插件实现，提供特定领域的能力。
 * 例如：文档读写技能、后端开发技能、前端开发技能等。
 */
public interface SkillPlugin extends Plugin {

    @Override
    default PluginType getType() {
        return PluginType.SKILL;
    }

    /**
     * 获取技能提供的系统提示词片段
     * 用于增强Agent在该领域的能力
     */
    String getSystemPromptFragment();

    /**
     * 获取技能提供的工具ID列表
     * 该技能依赖的工具插件ID
     */
    List<String> getRequiredToolIds();

    /**
     * 获取技能适用的任务类型描述
     */
    List<String> getTaskTypeDescriptions();

    /**
     * 判断该技能是否适用于给定的用户消息
     */
    default boolean isApplicable(String userMessage) {
        return true; // 默认适用于所有消息
    }
}
