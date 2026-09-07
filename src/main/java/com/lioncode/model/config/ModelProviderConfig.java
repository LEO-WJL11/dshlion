package com.lioncode.model.config;

import java.util.List;
import java.util.Map;

/**
 * 模型提供商配置
 * 
 * 包含所有国内外主流服务商模板，每个模板内置普通按量端点；
 * 支持Token-Plan的厂商额外内置Token-Plan订阅网关端点。
 */
public record ModelProviderConfig(
    /** 提供商ID */
    String providerId,
    /** 提供商显示名称 */
    String displayName,
    /** 普通按量端点Base URL */
    String standardBaseUrl,
    /** Token-Plan端点Base URL（不支持则为null） */
    String tokenPlanBaseUrl,
    /** 是否支持Token-Plan */
    boolean supportsTokenPlan,
    /** 默认模型列表 */
    List<String> defaultModels,
    /** 协议类型 */
    String protocolType
) {
    /**
     * 内置提供商模板列表
     */
    public static List<ModelProviderConfig> BUILTIN_PROVIDERS = List.of(
        // ==================== 支持Token-Plan的厂商 ====================
        new ModelProviderConfig("aliyun-bailian", "阿里百炼", 
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1", true,
            List.of("qwen-turbo", "qwen-plus", "qwen-max"), "openai"),

        new ModelProviderConfig("xiaomi-mimo", "小米MiMo",
            "https://api.xiaomimimo.com/v1",
            "https://api.xiaomimimo.com/v1", true,
            List.of("mimo-v2.5", "mimo-v2.5-pro"), "openai"),

        new ModelProviderConfig("volcengine-ark", "火山方舟",
            "https://ark.cn-beijing.volces.com/api/v3",
            "https://ark.cn-beijing.volces.com/api/v3", true,
            List.of("doubao-pro-32k", "doubao-lite-32k"), "openai"),

        new ModelProviderConfig("tencent-hunyuan", "腾讯混元",
            "https://api.hunyuan.cloud.tencent.com/v1",
            "https://api.hunyuan.cloud.tencent.com/v1", true,
            List.of("hunyuan-pro", "hunyuan-standard"), "openai"),

        // ==================== 不支持Token-Plan的厂商 ====================
        new ModelProviderConfig("modelscope", "魔搭ModelScope",
            "https://api-inference.modelscope.cn/v1", null, false,
            List.of("Qwen/Qwen2.5-72B-Instruct"), "openai"),

        new ModelProviderConfig("deepseek", "DeepSeek",
            "https://api.deepseek.com/v1", null, false,
            List.of("deepseek-v4-flash", "deepseek-v4-pro"), "openai"),

        new ModelProviderConfig("moonshot", "Moonshot Kimi",
            "https://api.moonshot.cn/v1", null, false,
            List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"), "openai"),

        new ModelProviderConfig("zhipu", "智谱GLM",
            "https://open.bigmodel.cn/api/paas/v4", null, false,
            List.of("glm-4", "glm-4-flash"), "openai"),

        new ModelProviderConfig("siliconflow", "硅基流动",
            "https://api.siliconflow.cn/v1", null, false,
            List.of("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3"), "openai"),

        new ModelProviderConfig("baichuan", "百川",
            "https://api.baichuan-ai.com/v1", null, false,
            List.of("Baichuan4"), "openai"),

        new ModelProviderConfig("lingyiwanwu", "零一万物",
            "https://api.lingyiwanwu.com/v1", null, false,
            List.of("yi-large", "yi-medium"), "openai"),

        new ModelProviderConfig("spark", "讯飞星火",
            "https://spark-api-open.xf-yun.com/v1", null, false,
            List.of("generalv3.5", "4.0Ultra"), "openai"),

        new ModelProviderConfig("qianfan", "百度千帆",
            "https://qianfan.baidubce.com/v2", null, false,
            List.of("ernie-4.0-8k", "ernie-3.5-8k"), "openai"),

        new ModelProviderConfig("minimax", "MiniMax",
            "https://api.minimax.chat/v1", null, false,
            List.of("abab6.5-chat", "MiniMax-Text-01"), "openai"),

        new ModelProviderConfig("stepfun", "阶跃星辰",
            "https://api.stepfun.com/v1", null, false,
            List.of("step-1-8k", "step-1-32k", "step-2-16k"), "openai"),

        new ModelProviderConfig("openai", "OpenAI",
            "https://api.openai.com/v1", null, false,
            List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"), "openai"),

        new ModelProviderConfig("groq", "Groq",
            "https://api.groq.com/openai/v1", null, false,
            List.of("llama-3.1-70b-versatile", "mixtral-8x7b-32768"), "openai"),

        new ModelProviderConfig("gemini", "Gemini兼容端点",
            "https://generativelanguage.googleapis.com/v1beta/openai", null, false,
            List.of("gemini-1.5-pro", "gemini-1.5-flash", "gemini-2.0-flash"), "openai"),

        new ModelProviderConfig("openrouter", "OpenRouter",
            "https://openrouter.ai/api/v1", null, false,
            List.of("anthropic/claude-3.5-sonnet", "google/gemini-2.0-flash"), "openai"),

        new ModelProviderConfig("anthropic", "Anthropic Claude",
            "https://api.anthropic.com", null, false,
            List.of("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022"), "anthropic")
    );
}
