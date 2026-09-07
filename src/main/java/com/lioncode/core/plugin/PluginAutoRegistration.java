package com.lioncode.core.plugin;

import com.lioncode.core.plugin.skill.SkillPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件自动注册
 * 
 * Spring Boot启动时自动将所有Skill和Tool插件注册到PluginRegistry。
 * 实现核心runtime与业务插件代码的解耦。
 */
@Component
public class PluginAutoRegistration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginAutoRegistration.class);

    private final PluginRegistry pluginRegistry;
    private final List<SkillPlugin> skillPlugins;
    private final List<ToolPlugin> toolPlugins;

    public PluginAutoRegistration(PluginRegistry pluginRegistry,
                                   List<SkillPlugin> skillPlugins,
                                   List<ToolPlugin> toolPlugins) {
        this.pluginRegistry = pluginRegistry;
        this.skillPlugins = skillPlugins;
        this.toolPlugins = toolPlugins;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== 插件自动注册开始 ===");
        
        // 注册Skill技能插件
        for (SkillPlugin skill : skillPlugins) {
            pluginRegistry.register(skill);
            log.info("  ✓ 技能插件: {} - {}", skill.getId(), skill.getName());
        }

        // 注册Tool工具插件
        for (ToolPlugin tool : toolPlugins) {
            pluginRegistry.register(tool);
            log.info("  ✓ 工具插件: {} - {}", tool.getId(), tool.getName());
        }

        log.info("=== 插件自动注册完成 === 共 {} 个插件（{} 个技能 + {} 个工具）",
            pluginRegistry.size(), skillPlugins.size(), toolPlugins.size());
    }
}
