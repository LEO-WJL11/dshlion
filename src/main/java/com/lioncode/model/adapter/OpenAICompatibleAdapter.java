package com.lioncode.model.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lioncode.core.agent.ThinkingLevel;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI兼容接口适配器
 * 
 * 支持所有OpenAI兼容接口的服务商：
 * - 国内：阿里百炼、小米MiMo、火山方舟、腾讯混元、魔搭、DeepSeek、Kimi等
 * - 国外：OpenAI、Groq、Gemini兼容端点、OpenRouter
 * - 本地：LM-Studio、llama.cpp等GGUF推理服务
 * 
 * 特性：
 * - 同步/流式调用
 * - 工具调用（function calling）
 * - 思考等级参数（本地GGUF服务不传递）
 * - 自动获取模型列表
 */
@Component
public class OpenAICompatibleAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleAdapter.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper mapper = new ObjectMapper();

    private String baseUrl = "";
    private String apiKey = "";
    private final OkHttpClient httpClient;

    public OpenAICompatibleAdapter() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public String getName() {
        return "OpenAI兼容适配器";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.OPENAI_COMPATIBLE;
    }

    @Override
    public void updateConfig(Map<String, Object> config) {
        if (config.containsKey("baseUrl")) {
            this.baseUrl = (String) config.get("baseUrl");
        }
        if (config.containsKey("apiKey")) {
            this.apiKey = (String) config.get("apiKey");
        }
        log.info("OpenAI适配器配置已更新: baseUrl={}", baseUrl);
    }

    @Override
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                              List<Map<String, Object>> tools) {
        try {
            return chatInternal(messages, model, thinkingLevel, tools);
        } catch (RuntimeException first) {
            // 安全网：如果API拒绝工具定义（常见HTTP 400），去掉工具重试一次，Agent不会死
            if (tools != null && !tools.isEmpty() && first.getMessage() != null
                    && first.getMessage().contains("HTTP 400")) {
                log.warn("携带工具定义调用失败，去掉工具重试（降级为XML/JSON文本工具调用）: {}", first.getMessage());
                return chatInternal(messages, model, null, List.of());
            }
            throw first;
        }
    }

    /**
     * 实际执行同步chat调用
     */
    private ModelResponse chatInternal(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                       List<Map<String, Object>> tools) {
        try {
            ObjectNode request = buildRequest(messages, model, thinkingLevel, false, tools);
            String jsonBody = mapper.writeValueAsString(request);
            
            Request httpRequest = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    throw new RuntimeException("模型调用失败: HTTP " + response.code() + " - " + errorBody);
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody);
            }
        } catch (Exception e) {
            log.error("OpenAI兼容接口调用失败", e);
            throw new RuntimeException("模型调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ModelChunk> chatStream(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                       List<Map<String, Object>> tools) {
        return Flux.create(sink -> startStream(sink, messages, model, thinkingLevel, tools, true));
    }

    /**
     * 启动流式请求；工具定义被API拒绝（HTTP 400）时自动去掉工具重试一次
     */
    private void startStream(reactor.core.publisher.FluxSink<ModelChunk> sink, List<ChatMessage> messages,
                             String model, ThinkingLevel thinkingLevel, List<Map<String, Object>> tools,
                             boolean canRetryWithoutTools) {
        try {
            ObjectNode request = buildRequest(messages, model, thinkingLevel, true, tools);
            String jsonBody = mapper.writeValueAsString(request);

            Request httpRequest = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();

            EventSource.Factory factory = EventSources.createFactory(httpClient);
            factory.newEventSource(httpRequest, new EventSourceListener() {
                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    if ("[DONE]".equals(data)) {
                        sink.complete();
                        return;
                    }
                    try {
                        ModelChunk chunk = parseStreamChunk(data);
                        sink.next(chunk);
                    } catch (Exception e) {
                        log.warn("解析流式数据失败: {}", data, e);
                    }
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    // 安全网：工具定义被API拒绝（HTTP 400）时，去掉工具重试一次
                    if (canRetryWithoutTools && response != null && response.code() == 400
                            && tools != null && !tools.isEmpty()) {
                        log.warn("流式携带工具定义调用失败(HTTP 400)，去掉工具重试: {}", 
                            response.message());
                        startStream(sink, messages, model, null, List.of(), false);
                        return;
                    }
                    if (t != null) {
                        sink.error(t);
                    } else {
                        sink.complete();
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    sink.complete();
                }
            });
        } catch (Exception e) {
            sink.error(e);
        }
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/models")
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("获取模型列表失败: HTTP {}", response.code());
                    return List.of();
                }
                String body = response.body() != null ? response.body().string() : "";
                return parseModels(body);
            }
        } catch (Exception e) {
            log.error("获取模型列表异常", e);
            return List.of();
        }
    }

    /**
     * 构建请求体
     */
    private ObjectNode buildRequest(List<ChatMessage> messages, String model, 
                                      ThinkingLevel thinkingLevel, boolean stream,
                                      List<Map<String, Object>> tools) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);
        request.put("stream", stream);

        // 消息数组
        ArrayNode messagesNode = request.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            
            // 工具调用（过滤掉name为空的无效工具调用，防止API报错）
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsNode = msgNode.putArray("tool_calls");
                for (ChatMessage.ToolCall tc : msg.toolCalls()) {
                    // 跳过name为空或null的工具调用
                    if (tc.name() == null || tc.name().isBlank()) {
                        log.warn("跳过无效工具调用（name为空）: id={}", tc.id());
                        continue;
                    }
                    ObjectNode tcNode = toolCallsNode.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode funcNode = tcNode.putObject("function");
                    funcNode.put("name", tc.name());
                    funcNode.put("arguments", tc.arguments() != null ? mapper.valueToTree(tc.arguments()).toString() : "{}");
                }
                // 如果所有工具调用都被过滤掉了，移除空数组
                if (toolCallsNode.isEmpty()) {
                    msgNode.remove("tool_calls");
                }
            }
            
            // 思考内容（thinking模式必须原样回传，否则API报400）
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            // 工具调用ID
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 工具定义（function calling）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = request.putArray("tools");
            for (Map<String, Object> toolDef : tools) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("type", "function");
                ObjectNode funcNode = toolNode.putObject("function");
                funcNode.put("name", (String) toolDef.get("name"));
                funcNode.put("description", (String) toolDef.get("description"));
                funcNode.set("parameters", mapper.valueToTree(toolDef.get("parameters")));
            }
        }

        // 思考等级（仅云端API传递，本地GGUF不传递）
        if (thinkingLevel != null && !isLocalEndpoint()) {
            request.put("reasoning_effort", thinkingLevel.getCode());
        }

        return request;
    }

    /**
     * 判断是否为本地端点
     */
    private boolean isLocalEndpoint() {
        if (baseUrl == null) return false;
        String lower = baseUrl.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0");
    }

    /**
     * 解析同步响应
     */
    private ModelResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        
        if (choices == null || choices.isEmpty()) {
            return new ModelResponse("", List.of(), true, null, "empty", null);
        }

        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        
        String content = message.has("content") ? message.get("content").asText() : "";
        String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : null;

        // 思考内容：DeepSeek等thinking模式要求原样回传
        String reasoningContent = message.has("reasoning_content") 
            ? message.get("reasoning_content").asText() : null;

        // 解析工具调用（校验name有效性）
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        if (message.has("tool_calls")) {
            for (JsonNode tc : message.get("tool_calls")) {
                String id = tc.has("id") ? tc.get("id").asText() : ("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                String name = null;
                String argsStr = "{}";
                if (tc.has("function")) {
                    JsonNode func = tc.get("function");
                    name = func.has("name") ? func.get("name").asText(null) : null;
                    argsStr = func.has("arguments") ? func.get("arguments").asText("{}") : "{}";
                }
                // 跳过name为空的无效工具调用
                if (name == null || name.isBlank()) {
                    log.warn("跳过API返回的无效工具调用（name为空）: id={}", id);
                    continue;
                }
                Map<String, Object> args = mapper.readValue(argsStr, Map.class);
                toolCalls.add(new ChatMessage.ToolCall(id, name, args));
            }
        }

        // 解析token使用
        ModelResponse.TokenUsage usage = null;
        if (root.has("usage")) {
            JsonNode usageNode = root.get("usage");
            usage = new ModelResponse.TokenUsage(
                usageNode.get("prompt_tokens").asInt(),
                usageNode.get("completion_tokens").asInt(),
                usageNode.get("total_tokens").asInt()
            );
        }

        return new ModelResponse(content, toolCalls, true, usage, finishReason, reasoningContent);
    }

    /**
     * 解析流式响应块
     */
    private ModelChunk parseStreamChunk(String data) throws Exception {
        JsonNode root = mapper.readTree(data);
        JsonNode choices = root.get("choices");
        
        if (choices == null || choices.isEmpty()) {
            return new ModelChunk("", List.of(), false, null, null);
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");
        
        String content = delta.has("content") ? delta.get("content").asText() : "";
        String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : null;

        // 思考内容增量（thinking模式）
        String reasoningDelta = delta.has("reasoning_content") 
            ? delta.get("reasoning_content").asText() : null;

        // 解析工具调用增量
        List<ModelChunk.ToolCallDelta> toolCallDeltas = new ArrayList<>();
        if (delta.has("tool_calls")) {
            for (JsonNode tc : delta.get("tool_calls")) {
                int index = tc.has("index") ? tc.get("index").asInt() : 0;
                String id = tc.has("id") ? tc.get("id").asText() : null;
                String nameDelta = null;
                String argsDelta = null;
                if (tc.has("function")) {
                    JsonNode func = tc.get("function");
                    // 安全获取name，避免空值
                    if (func.has("name") && !func.get("name").asText("").isBlank()) {
                        nameDelta = func.get("name").asText();
                    }
                    argsDelta = func.has("arguments") ? func.get("arguments").asText() : null;
                }
                toolCallDeltas.add(new ModelChunk.ToolCallDelta(index, id, nameDelta, argsDelta));
            }
        }

        boolean finished = finishReason != null;
        return new ModelChunk(content, toolCallDeltas, finished, finishReason, reasoningDelta);
    }

    /**
     * 解析模型列表
     */
    private List<ModelInfo> parseModels(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.get("data");
        
        if (data == null) return List.of();

        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : data) {
            String id = model.get("id").asText();
            String owner = model.has("owned_by") ? model.get("owned_by").asText() : "unknown";
            
            // 根据模型名称判断是否支持思考等级
            boolean supportsThinking = supportsThinking(id);
            List<ModelInfo.ThinkingLevelOption> thinkingLevels = getThinkingLevels(id);
            
            // 解析模型元数据（如果有）
            Integer maxContext = model.has("context_length") ? 
                model.get("context_length").asInt() : null;
            Integer maxOutput = model.has("max_output_tokens") ? 
                model.get("max_output_tokens").asInt() : null;
            
            models.add(new ModelInfo(
                id, id, owner,
                supportsThinking, true,
                isLocalEndpoint() ? ModelInfo.ModelSource.LOCAL_GGUF : ModelInfo.ModelSource.CLOUD_API,
                null, null, thinkingLevels, maxContext, maxOutput
            ));
        }
        return models;
    }

    /**
     * 判断模型是否支持思考等级
     */
    private boolean supportsThinking(String modelId) {
        String lower = modelId.toLowerCase();
        // DeepSeek Reasoner/V4系列
        if (lower.contains("reasoner") || lower.contains("r1") || lower.contains("deepseek-v4")) return true;
        // Claude 3.5 Sonnet及更高版本
        if (lower.contains("claude") && (lower.contains("3.5") || lower.contains("3-5") || lower.contains("4"))) return true;
        // Qwen系列
        if (lower.contains("qwen") && lower.contains("max")) return true;
        // MiMo系列
        if (lower.contains("mimo")) return true;
        // Gemini Pro
        if (lower.contains("gemini") && lower.contains("pro")) return true;
        // 本地GGUF默认不支持
        if (isLocalEndpoint()) return false;
        return false;
    }

    /**
     * 根据模型ID获取支持的思考等级列表
     */
    private List<ModelInfo.ThinkingLevelOption> getThinkingLevels(String modelId) {
        String lower = modelId.toLowerCase();
        
        // DeepSeek Reasoner/V4系列 - 支持完整思考等级
        if (lower.contains("reasoner") || lower.contains("r1") || lower.contains("deepseek-v4")) {
            return List.of(
                new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速响应，简单推理", 1024, false),
                new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准推理，平衡模式", 4096, true),
                new ModelInfo.ThinkingLevelOption("HIGH", "深度", "深度推理，复杂任务", 16384, false),
                new ModelInfo.ThinkingLevelOption("MAX", "最强", "最强推理，极限任务", 32768, false)
            );
        }
        
        // Claude系列 - 支持扩展思考
        if (lower.contains("claude")) {
            if (lower.contains("4") || lower.contains("3.5") || lower.contains("3-5")) {
                return List.of(
                    new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速响应", 1024, false),
                    new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准思考", 4096, true),
                    new ModelInfo.ThinkingLevelOption("HIGH", "扩展", "扩展思考模式", 16384, false)
                );
            }
        }
        
        // MiMo系列
        if (lower.contains("mimo")) {
            return List.of(
                new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速模式", 1024, false),
                new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准模式", 4096, true),
                new ModelInfo.ThinkingLevelOption("HIGH", "深度", "深度思考", 16384, false)
            );
        }
        
        // Qwen Max系列
        if (lower.contains("qwen") && lower.contains("max")) {
            return List.of(
                new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速响应", 1024, false),
                new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准推理", 4096, true),
                new ModelInfo.ThinkingLevelOption("HIGH", "深度", "深度推理", 16384, false)
            );
        }
        
        // Gemini Pro系列
        if (lower.contains("gemini") && lower.contains("pro")) {
            return List.of(
                new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速模式", 1024, false),
                new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准思考", 4096, true),
                new ModelInfo.ThinkingLevelOption("HIGH", "深度", "深度思考", 16384, false)
            );
        }
        
        // 其他模型 - 不支持思考等级
        return List.of();
    }
}
