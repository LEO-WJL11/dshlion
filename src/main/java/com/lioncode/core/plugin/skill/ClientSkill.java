package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 客户端开发技能包
 * 
 * 桌面客户端程序开发相关能力。
 * 支持Electron、Qt、JavaFX、WPF、.NET MAUI等桌面开发框架。
 */
@Component
public class ClientSkill implements SkillPlugin {

    @Override
    public String getId() {
        return "skill.client";
    }

    @Override
    public String getName() {
        return "客户端开发技能";
    }

    @Override
    public String getDescription() {
        return "桌面客户端程序开发相关能力，支持Electron、Qt、JavaFX等框架";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<String> getTags() {
        return List.of("client", "desktop", "electron", "qt", "javafx", "客户端", "桌面");
    }

    @Override
    public String getSystemPromptFragment() {
        return """
            你具备专业的桌面客户端开发能力，包括：
            - Electron跨平台桌面应用开发
            - Qt/C++桌面应用开发
            - JavaFX桌面应用开发
            - WPF/.NET桌面应用开发
            - 桌面应用UI设计和交互
            - 系统托盘、通知、文件关联等原生功能
            - 应用打包和分发
            
            开发客户端代码时请：
            1. 考虑跨平台兼容性
            2. 优化应用启动速度和内存占用
            3. 处理好窗口管理和生命周期
            4. 实现良好的用户交互体验
            5. 处理好异常和崩溃恢复
            """;
    }

    @Override
    public List<String> getRequiredToolIds() {
        return List.of(
            "tool.file.read",
            "tool.file.write",
            "tool.file.modify",
            "tool.shell.execute"
        );
    }

    @Override
    public List<String> getTaskTypeDescriptions() {
        return List.of(
            "桌面应用开发",
            "Electron应用开发",
            "Qt应用开发",
            "JavaFX应用开发",
            "桌面UI设计",
            "应用打包分发",
            "系统原生功能集成"
        );
    }

    @Override
    public boolean isApplicable(String userMessage) {
        String lower = userMessage.toLowerCase();
        return lower.contains("客户端") || lower.contains("desktop") || lower.contains("electron")
            || lower.contains("qt") || lower.contains("javafx") || lower.contains("桌面")
            || lower.contains("wpf");
    }

    @Override
    public void initialize() {
        // 客户端技能初始化
    }
}
