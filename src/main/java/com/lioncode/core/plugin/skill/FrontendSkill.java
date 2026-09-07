package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 前端开发技能包
 * 
 * 网页前端编写、样式调试、组件开发、接口对接等前端相关能力。
 * 支持React、Vue、Angular、原生HTML/CSS/JS等。
 */
@Component
public class FrontendSkill implements SkillPlugin {

    @Override
    public String getId() {
        return "skill.frontend";
    }

    @Override
    public String getName() {
        return "前端开发技能";
    }

    @Override
    public String getDescription() {
        return "网页前端编写、样式调试、组件开发、接口对接等前端相关能力";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<String> getTags() {
        return List.of("frontend", "react", "vue", "angular", "html", "css", "js", "前端");
    }

    @Override
    public String getSystemPromptFragment() {
        return """
            你具备专业的前端开发能力，包括：
            - 网页前端代码编写（HTML、CSS、JavaScript、TypeScript）
            - 主流框架开发（React、Vue、Angular）
            - 组件设计和开发
            - 样式调试和响应式布局
            - 前后端接口对接
            - 前端性能优化
            - 浏览器兼容性处理
            
            开发前端代码时请：
            1. 使用语义化HTML标签
            2. CSS使用现代布局方式（Flexbox、Grid）
            3. 组件保持单一职责
            4. 注意可访问性（a11y）
            5. 优化加载性能
            """;
    }

    @Override
    public List<String> getRequiredToolIds() {
        return List.of(
            "tool.file.read",
            "tool.file.write",
            "tool.file.modify",
            "tool.file.search",
            "tool.shell.execute"
        );
    }

    @Override
    public List<String> getTaskTypeDescriptions() {
        return List.of(
            "前端代码编写",
            "组件开发",
            "样式调试",
            "响应式布局",
            "前后端对接",
            "前端性能优化",
            "浏览器兼容"
        );
    }

    @Override
    public boolean isApplicable(String userMessage) {
        String lower = userMessage.toLowerCase();
        return lower.contains("前端") || lower.contains("frontend") || lower.contains("html")
            || lower.contains("css") || lower.contains("javascript") || lower.contains("react")
            || lower.contains("vue") || lower.contains("页面") || lower.contains("样式");
    }

    @Override
    public void initialize() {
        // 前端技能初始化
    }
}
