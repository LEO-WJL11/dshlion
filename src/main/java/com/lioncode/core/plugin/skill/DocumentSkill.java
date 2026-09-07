package com.lioncode.core.plugin.skill;

import com.lioncode.core.plugin.Plugin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文档读写技能包
 * 
 * 专门负责撰写文档、阅读解析各类文档、文档整理、文档格式转换相关能力。
 * 支持Markdown、纯文本、HTML、JSON、YAML等格式。
 */
@Component
public class DocumentSkill implements SkillPlugin {

    @Override
    public String getId() {
        return "skill.document";
    }

    @Override
    public String getName() {
        return "文档读写技能";
    }

    @Override
    public String getDescription() {
        return "专门负责撰写文档、阅读解析各类文档、文档整理、文档格式转换相关能力";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<String> getTags() {
        return List.of("document", "markdown", "text", "format", "文档", "写作");
    }

    @Override
    public String getSystemPromptFragment() {
        return """
            你具备专业的文档处理能力，包括：
            - 撰写各类技术文档、README、API文档、设计文档
            - 阅读和解析Markdown、纯文本、HTML、JSON、YAML等格式文档
            - 文档格式转换（Markdown转HTML、JSON转YAML等）
            - 文档结构优化和内容整理
            - 生成文档大纲和目录
            
            处理文档时请：
            1. 保持文档结构清晰，使用合适的标题层级
            2. 代码块使用正确的语言标记
            3. 重要内容使用粗体或列表突出
            4. 保持一致的格式风格
            """;
    }

    @Override
    public List<String> getRequiredToolIds() {
        return List.of(
            "tool.file.read",
            "tool.file.write",
            "tool.file.search",
            "tool.file.modify"
        );
    }

    @Override
    public List<String> getTaskTypeDescriptions() {
        return List.of(
            "撰写技术文档",
            "阅读和解析文档",
            "文档格式转换",
            "文档结构优化",
            "生成API文档",
            "编写README",
            "文档内容整理"
        );
    }

    @Override
    public boolean isApplicable(String userMessage) {
        String lower = userMessage.toLowerCase();
        return lower.contains("文档") || lower.contains("doc") || lower.contains("readme")
            || lower.contains("markdown") || lower.contains("写") || lower.contains("撰写")
            || lower.contains("整理") || lower.contains("格式");
    }

    @Override
    public void initialize() {
        // 文档技能初始化
    }
}
