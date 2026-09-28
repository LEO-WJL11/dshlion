package com.lioncode.model.adapter;

import com.lioncode.core.agent.ThinkingLevel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 模型协议适配器接口
 * 
 * 提供统一的模型调用抽象。盒子出厂使用OpenAI兼容协议对接本地模型运行时；
 * 适配器支持热切换，切换过程保护关键会话状态。
 */
public interface ModelAdapter {

    /**
     * 获取适配器名称
     */
    String getName();

    /**
     * 获取适配器类型
     */
    AdapterType getType();

    /**
     * 该端点是否更适口「文本工具调用」约定（系统提示词里的 &lt;tool_call&gt; 格式）。
     *
     * 返回 true 的端点不下发原生 tools 定义，工具调用完全走提示词约定。
     * 目前只有盒子内置的本地 GGUF 运行时会返回 true，理由有两条：
     *   1. 本地 llama-server 启动参数里没有 --jinja，请求体里的 tools 会被直接忽略；
     *   2. 本地模型是按「文本 &lt;tool_call&gt; 约定」微调的，给它塞原生工具通道会偏离训练分布。
     * 云端 OpenAI 兼容 API 走原生 function calling 更稳（结构化、不用解析文本）。
     */
    default boolean prefersTextToolCalls() {
        return false;
    }

    /**
     * 该端点是否**刚刚拒绝过** tools 定义（HTTP 400）。
     *
     * 一旦为 true，AgentLoop 下一轮就不再把 tools 下发给它，并且系统提示词会
     * 改回完整的文本 &lt;tool_call&gt; 说明 —— 否则会出现「工具定义被拒 +
     * 提示词又不教文本格式」的双输局面（模型完全不知道该怎么调工具）。
     * 端点配置变更时应当复位。
     */
    default boolean toolDefinitionsRejected() {
        return false;
    }

    /**
     * 同步调用模型
     * 
     * @param messages 消息列表
     * @param model 模型名称
     * @param thinkingLevel 思考等级（本地GGUF服务忽略此参数）
     * @param tools 可用工具定义列表（OpenAI function calling格式），可为null或空
     * @return 模型响应
     */
    ModelResponse chat(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                       List<Map<String, Object>> tools);

    /**
     * 带额外参数的同步调用（可选能力，默认实现忽略额外参数，退回普通 chat）
     *
     * 用途：会话标题生成这类"小任务"需要精细控制请求体，例如
     *   - 关掉思考模型的思考过程（reasoning 会把 token 预算吃光，正文变成空串）
     *   - 限制 max_tokens
     * 这些参数不该污染主对话，所以单独开一个入口。
     *
     * @param extraBody 合并进请求体的额外字段，可为 null
     * @param maxTokens 输出上限，null 表示用服务端默认
     */
    default ModelResponse chatWithOptions(List<ChatMessage> messages, String model,
                                          ThinkingLevel thinkingLevel,
                                          List<Map<String, Object>> tools,
                                          Map<String, Object> extraBody, Integer maxTokens) {
        return chat(messages, model, thinkingLevel, tools);
    }

    /**
     * 流式调用模型
     * 
     * @param messages 消息列表
     * @param model 模型名称
     * @param thinkingLevel 思考等级
     * @param tools 可用工具定义列表（OpenAI function calling格式），可为null或空
     * @return 流式响应
     */
    Flux<ModelChunk> chatStream(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                List<Map<String, Object>> tools);

    /**
     * 获取可用模型列表
     */
    List<ModelInfo> getAvailableModels();

    /**
     * 检查适配器是否可用
     */
    boolean isAvailable();

    /**
     * 更新配置
     */
    void updateConfig(Map<String, Object> config);

    /**
     * 获取适配器类型枚举
     */
    enum AdapterType {
        /** OpenAI兼容接口（盒子本地模型运行时使用） */
        OPENAI_COMPATIBLE,
        /** Messages API原生接口（需显式配置端点） */
        ANTHROPIC
    }
}
