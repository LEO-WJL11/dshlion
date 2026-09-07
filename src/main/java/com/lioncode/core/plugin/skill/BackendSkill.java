package com.lioncode.core.plugin.skill;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 后端开发技能包
 * 
 * 后端代码编写、调试、编译排错、接口设计、依赖处理等后端相关能力。
 * 支持Java、Python、Go、Node.js等主流后端语言。
 */
@Component
public class BackendSkill implements SkillPlugin {

    @Override
    public String getId() {
        return "skill.backend";
    }

    @Override
    public String getName() {
        return "后端开发技能";
    }

    @Override
    public String getDescription() {
        return "后端代码编写、调试、编译排错、接口设计、依赖处理等后端相关能力";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<String> getTags() {
        return List.of("backend", "java", "python", "go", "node", "api", "后端", "服务端");
    }

    @Override
    public String getSystemPromptFragment() {
        return """
            你具备专业的后端开发能力，包括：
            - 后端代码编写和重构（Java、Python、Go、Node.js等）
            - RESTful API和GraphQL接口设计
            - 数据库设计和SQL优化
            - 依赖管理和构建工具使用（Maven、Gradle、npm等）
            - 编译错误排查和修复
            - 性能优化和代码审查
            - 单元测试和集成测试编写
            
            开发后端代码时请：
            1. 遵循语言和框架的最佳实践
            2. 编写清晰的错误处理逻辑
            3. 添加必要的注释和文档
            4. 考虑安全性和性能
            5. 编写可测试的代码
            """;
    }

    @Override
    public List<String> getRequiredToolIds() {
        return List.of(
            "tool.file.read",
            "tool.file.write",
            "tool.file.modify",
            "tool.file.search",
            "tool.shell.execute",
            "tool.git.status",
            "tool.git.commit"
        );
    }

    @Override
    public List<String> getTaskTypeDescriptions() {
        return List.of(
            "后端代码编写",
            "API接口设计",
            "数据库设计",
            "编译错误修复",
            "依赖管理",
            "性能优化",
            "代码重构",
            "单元测试编写"
        );
    }

    @Override
    public boolean isApplicable(String userMessage) {
        String lower = userMessage.toLowerCase();
        return lower.contains("后端") || lower.contains("backend") || lower.contains("api")
            || lower.contains("java") || lower.contains("python") || lower.contains("服务端")
            || lower.contains("数据库") || lower.contains("接口") || lower.contains("编译");
    }

    @Override
    public void initialize() {
        // 后端技能初始化
    }
}
