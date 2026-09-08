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

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Claude原生接口适配器
 * 
 * 使用Anthropic Messages API格式：
 * - POST /v1/messages
 * - x-api-key认证
 * - anthropic-version头
 * - 不同于OpenAI的消息格式
 * 
 * 特性：
 * - 同步/流式调用
 * - 工具调用（tool_use）
 * - 思考等级（thinking参数）
 * - 自动获取模型列表
 */
@Component
public class AnthropicAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private String baseUrl = "https://api.anthropic.com";
    private String apiKey = "";
    private final OkHttpClient httpClient;

    public AnthropicAdapter() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public String getName() {
        return "Anthropic Claude原生适配器";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.ANTHROPIC;
    }

    @Override
    public void updateConfig(Map<String, Object> config) {
        if (config.containsKey("baseUrl")) {
            this.baseUrl = (String) config.get("baseUrl");
        }
        if (config.containsKey("apiKey")) {
            this.apiKey = (String) config.get("apiKey");
        }
        log.info("Anthropic适配器配置已更新: baseUrl={}", baseUrl);
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                              List<Map<String, Object>> tools) {
        try {
            ObjectNode request = buildRequest(messages, model, thinkingLevel, false, tools);
            String jsonBody = mapper.writeValueAsString(request);

            Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    throw new RuntimeException("Anthropic调用失败: HTTP " + response.code() + " - " + errorBody);
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody);
            }
        } catch (Exception e) {
            log.error("Anthropic接口调用失败", e);
            throw new RuntimeException("Anthropic调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ModelChunk> chatStream(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                       List<Map<String, Object>> tools) {
        return Flux.create(sink -> {
            try {
                ObjectNode request = buildRequest(messages, model, thinkingLevel, true, tools);
                String jsonBody = mapper.writeValueAsString(request);

                Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON_TYPE))
                    .build();

                EventSource.Factory factory = EventSources.createFactory(httpClient);
                factory.newEventSource(httpRequest, new EventSourceListener() {
                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        try {
                            JsonNode event = mapper.readTree(data);
                            String eventType = event.has("type") ? event.get("type").asText() : "";
                            
                            switch (eventType) {
                                case "content_block_delta" -> {
                                    JsonNode delta = event.get("delta");
                                    if (delta == null) break;
                                    int index = event.has("index") ? event.get("index").asInt() : 0;
                                    if ("text_delta".equals(delta.get("type").asText())) {
                                        String text = delta.get("text").asText();
                                        sink.next(new ModelChunk(text, List.of(), false, null, null));
                                    } else if ("input_json_delta".equals(delta.get("type").asText())) {
                                        // 工具调用参数增量：与AgentLoop的累积器按index对接
                                        String partialJson = delta.has("partial_json") 
                                            ? delta.get("partial_json").asText() : "";
                                        sink.next(new ModelChunk("",
                                            List.of(new ModelChunk.ToolCallDelta(index, null, null, partialJson)),
                                            false, null, null));
                                    }
                                }
                                case "content_block_start" -> {
                                    JsonNode block = event.get("content_block");
                                    if (block != null && "tool_use".equals(block.get("type").asText())) {
                                        // 工具调用开始：携带index，供参数增量按index累积
                                        String toolCallId = block.get("id").asText();
                                        String toolName = block.get("name").asText();
                                        int index = event.has("index") ? event.get("index").asInt() : 0;
                                        sink.next(new ModelChunk("",
                                            List.of(new ModelChunk.ToolCallDelta(index, toolCallId, toolName, null)),
                                            false, null, null));
                                    }
                                }
                                case "message_delta" -> {
                                    JsonNode delta = event.get("delta");
                                    if (delta != null && delta.has("stop_reason")) {
                                        String stopReason = delta.get("stop_reason").asText();
                                        sink.next(new ModelChunk("", List.of(), true, stopReason, null));
                                        sink.complete();
                                    }
                                }
                                case "message_stop" -> {
                                    sink.complete();
                                }
                            }
                        } catch (Exception e) {
                            log.warn("解析Anthropic流式数据失败: {}", data, e);
                        }
                    }

                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        if (t != null) {
                            sink.error(t);
                        } else {
                            sink.complete();
                        }
                    }
                });
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        // Anthropic没有公开的/models端点，返回已知模型及其思考等级
        return List.of(
            // Claude Sonnet 4 - 支持扩展思考
            new ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", "Anthropic",
                true, true, ModelInfo.ModelSource.CLOUD_API, null, null,
                List.of(
                    new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速响应，不启用思考", 1024, false),
                    new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准思考模式", 4096, true),
                    new ModelInfo.ThinkingLevelOption("HIGH", "扩展", "扩展思考，深度推理", 16384, false)
                ), 200000, 8192),
            
            // Claude 3.5 Haiku - 不支持扩展思考
            new ModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", "Anthropic",
                false, true, ModelInfo.ModelSource.CLOUD_API, null, null,
                List.of(), 200000, 8192),
            
            // Claude 3 Opus - 支持扩展思考
            new ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", "Anthropic",
                true, true, ModelInfo.ModelSource.CLOUD_API, null, null,
                List.of(
                    new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速响应", 1024, false),
                    new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准思考", 4096, true),
                    new ModelInfo.ThinkingLevelOption("HIGH", "扩展", "扩展思考模式", 16384, false),
                    new ModelInfo.ThinkingLevelOption("MAX", "最强", "最大思考深度", 32768, false)
                ), 200000, 4096),
            
            // Claude 3.5 Sonnet - 支持扩展思考
            new ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", "Anthropic",
                true, true, ModelInfo.ModelSource.CLOUD_API, null, null,
                List.of(
                    new ModelInfo.ThinkingLevelOption("LOW", "快速", "快速响应", 1024, false),
                    new ModelInfo.ThinkingLevelOption("MEDIUM", "标准", "标准思考", 4096, true),
                    new ModelInfo.ThinkingLevelOption("HIGH", "扩展", "扩展思考模式", 16384, false)
                ), 200000, 8192)
        );
    }

    /**
     * 构建Anthropic请求体
     */
    private ObjectNode buildRequest(List<ChatMessage> messages, String model,
                                      ThinkingLevel thinkingLevel, boolean stream,
                                      List<Map<String, Object>> tools) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);
        request.put("max_tokens", 8192);
        request.put("stream", stream);

        // 提取系统消息
        String systemPrompt = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .map(ChatMessage::content)
            .collect(java.util.stream.Collectors.joining("\n"));
        if (!systemPrompt.isBlank()) {
            request.put("system", systemPrompt.trim());
        }

        // 构建消息数组（排除system消息）
        ArrayNode messagesNode = request.putArray("messages");
        for (ChatMessage msg : messages) {
            if ("system".equals(msg.role())) continue;
            
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put("role", msg.role());
            
            // Anthropic使用content数组格式
            ArrayNode contentArray = msgNode.putArray("content");
            
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // 工具调用
                for (ChatMessage.ToolCall tc : msg.toolCalls()) {
                    ObjectNode toolUse = contentArray.addObject();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", tc.id());
                    toolUse.put("name", tc.name());
                    toolUse.set("input", mapper.valueToTree(tc.arguments()));
                }
                if (msg.content() != null && !msg.content().isBlank()) {
                    ObjectNode textBlock = contentArray.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.content());
                }
            } else if (msg.toolCallId() != null) {
                // 工具结果
                ObjectNode toolResult = contentArray.addObject();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", msg.toolCallId());
                toolResult.put("content", msg.content());
            } else {
                // 普通文本
                ObjectNode textBlock = contentArray.addObject();
                textBlock.put("type", "text");
                textBlock.put("text", msg.content());
            }
        }

        // 工具定义（Anthropic格式）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = request.putArray("tools");
            for (Map<String, Object> toolDef : tools) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("name", (String) toolDef.get("name"));
                toolNode.put("description", (String) toolDef.get("description"));
                toolNode.set("input_schema", mapper.valueToTree(toolDef.get("parameters")));
            }
        }

        // 思考等级（仅对支持的模型发送）
        if (thinkingLevel != null && supportsThinking(model)) {
            ObjectNode thinking = request.putObject("thinking");
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", thinkingLevel.getTokenBudget());
        }

        return request;
    }

    /**
     * 判断模型是否支持扩展思考
     */
    private boolean supportsThinking(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        // Claude 3.5 Sonnet及以上、Claude 3 Opus、Claude Sonnet 4支持扩展思考
        if (lower.contains("claude-sonnet-4") || lower.contains("claude-4")) return true;
        if (lower.contains("claude-3-opus")) return true;
        if (lower.contains("claude-3-5-sonnet") || lower.contains("claude-3.5-sonnet")) return true;
        // Claude 3.5 Haiku不支持扩展思考
        return false;
    }

    /**
     * 解析Anthropic响应
     */
    private ModelResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        
        StringBuilder contentBuilder = new StringBuilder();
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        
        JsonNode contentBlocks = root.get("content");
        if (contentBlocks != null) {
            for (JsonNode block : contentBlocks) {
                String type = block.get("type").asText();
                if ("text".equals(type)) {
                    contentBuilder.append(block.get("text").asText());
                } else if ("tool_use".equals(type)) {
                    String id = block.get("id").asText();
                    String name = block.get("name").asText();
                    Map<String, Object> input = mapper.convertValue(block.get("input"), Map.class);
                    toolCalls.add(new ChatMessage.ToolCall(id, name, input));
                }
            }
        }

        String content = contentBuilder.toString();

        String stopReason = root.has("stop_reason") ? root.get("stop_reason").asText() : null;
        boolean finished = "end_turn".equals(stopReason) || "tool_use".equals(stopReason);

        // Token使用
        ModelResponse.TokenUsage usage = null;
        if (root.has("usage")) {
            JsonNode usageNode = root.get("usage");
            usage = new ModelResponse.TokenUsage(
                usageNode.get("input_tokens").asInt(),
                usageNode.get("output_tokens").asInt(),
                usageNode.get("input_tokens").asInt() + usageNode.get("output_tokens").asInt()
            );
        }

        return new ModelResponse(content, toolCalls, finished, usage, stopReason, null);
    }
}
