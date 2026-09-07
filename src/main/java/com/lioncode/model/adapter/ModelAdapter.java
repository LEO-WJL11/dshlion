package com.lioncode.model.adapter;

import com.lioncode.core.agent.ThinkingLevel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 模型协议适配器接口
 * 
 * 提供统一的模型调用抽象，支持OpenAI兼容接口和Anthropic Claude原生接口。
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
        /** OpenAI兼容接口 */
        OPENAI_COMPATIBLE,
        /** Anthropic Claude原生接口 */
        ANTHROPIC
    }
}
