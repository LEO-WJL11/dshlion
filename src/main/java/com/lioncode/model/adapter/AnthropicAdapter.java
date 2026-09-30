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
 * 消息协议适配器（Messages API格式）
 * 
 * 使用 Messages API 格式：
 * - POST /v1/messages
 * - x-api-key认证
 * - 版本头
 * - 不同于OpenAI的消息格式
 * 
 * 说明：出厂只提供本地模型运行时（OpenAI兼容协议），
 * 此适配器不预设任何外部端点、不内置任何云端模型清单，
 * 必须显式配置端点后才可用。
 * 
 * 特性：
 * - 同步/流式调用
 * - 工具调用（tool_use）
 * - 自动获取模型列表
 */
@Component
public class AnthropicAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 本地场景不预设任何外部端点：必须显式配置才可用 */
    private volatile String baseUrl = "";
    private volatile String apiKey = "";
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
        if (config == null) {
            return;
        }
        // 不强转 String：配置是用户可写的 JSON，传数字/对象进来会抛 ClassCastException
        if (config.containsKey("baseUrl")) {
            this.baseUrl = safeStr(config.get("baseUrl"), this.baseUrl);
        }
        if (config.containsKey("apiKey")) {
            this.apiKey = safeStr(config.get("apiKey"), this.apiKey);
        }
        log.info("Anthropic适配器配置已更新: baseUrl={}", baseUrl);
    }

    /** 宽松转字符串：null → 用旧值；其它类型一律 String.valueOf（不抛 CCE） */
    private static String safeStr(Object value, String fallback) {
        if (value == null) {
            return fallback == null ? "" : fallback;
        }
        if (value instanceof String s) {
            return s;
        }
        return String.valueOf(value);
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
        } catch (RuntimeException e) {
            // 【为什么单独 catch RuntimeException】下面的 catch(Exception) 会把上面刚抛出的
            // "Anthropic调用失败: HTTP 400 - ..." 再包一层，日志和界面变成
            // "Anthropic调用失败: Anthropic调用失败: HTTP 400 - ..."（重复前缀 + 打一整条堆栈）
            log.warn("Anthropic接口调用失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Anthropic接口调用失败", e);
            throw new RuntimeException("Anthropic调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ModelChunk> chatStream(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                       List<Map<String, Object>> tools) {
        return Flux.create(sink -> {
            // 同 OpenAI 适配器：订阅者取消时要把 SSE 连接关掉，否则请求会一直跑到底
            EventSource[] holder = new EventSource[1];
            sink.onCancel(() -> cancelQuietly(holder[0]));
            sink.onDispose(() -> cancelQuietly(holder[0]));
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
                holder[0] = factory.newEventSource(httpRequest, new EventSourceListener() {
                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        try {
                            JsonNode event = mapper.readTree(data);
                            String eventType = event.has("type") ? event.get("type").asText() : "";
                            
                            switch (eventType) {
                                case "content_block_delta" -> {
                                    JsonNode delta = event.get("delta");
                                    if (delta == null || delta.isNull()) break;
                                    int index = event.has("index") ? event.get("index").asInt() : 0;
                                    String deltaType = textOf(delta, "type", "");
                                    if ("text_delta".equals(deltaType)) {
                                        String text = textOf(delta, "text", "");
                                        sink.next(new ModelChunk(text, List.of(), false, null, null));
                                    } else if ("input_json_delta".equals(deltaType)) {
                                        // 工具调用参数增量：与AgentLoop的累积器按index对接
                                        String partialJson = textOf(delta, "partial_json", "");
                                        sink.next(new ModelChunk("",
                                            List.of(new ModelChunk.ToolCallDelta(index, null, null, partialJson)),
                                            false, null, null));
                                    }
                                }
                                case "content_block_start" -> {
                                    JsonNode block = event.get("content_block");
                                    if (block != null && !block.isNull()
                                            && "tool_use".equals(textOf(block, "type", ""))) {
                                        // 工具调用开始：携带index，供参数增量按index累积
                                        String toolCallId = textOf(block, "id", null);
                                        String toolName = textOf(block, "name", null);
                                        int index = event.has("index") ? event.get("index").asInt() : 0;
                                        sink.next(new ModelChunk("",
                                            List.of(new ModelChunk.ToolCallDelta(index, toolCallId, toolName, null)),
                                            false, null, null));
                                    }
                                }
                                case "message_delta" -> {
                                    JsonNode delta = event.get("delta");
                                    if (delta != null && delta.has("stop_reason")
                                            && !delta.get("stop_reason").isNull()) {
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
        // 出厂只内置本地模型：此适配器不内置任何云端模型清单，
        // 避免UI或接口出现云端模型选项。
        return List.of();
    }

    /** 取消 SSE 连接；已经结束的 EventSource cancel() 是无害的空操作 */
    private static void cancelQuietly(EventSource source) {
        if (source == null) {
            return;
        }
        try {
            source.cancel();
        } catch (Exception e) {
            log.debug("取消流式请求失败（忽略）: {}", e.getMessage());
        }
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
     * 
     * 本地模型运行时使用OpenAI兼容协议，此适配器不参与思考等级协商。
     */
    private boolean supportsThinking(String modelId) {
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
        if (contentBlocks != null && contentBlocks.isArray()) {
            for (JsonNode block : contentBlocks) {
                // 逐字段判空：少一个 "type"/"text" 就 NPE 会让整次调用失败并触发重试
                String type = textOf(block, "type", "");
                if ("text".equals(type)) {
                    contentBuilder.append(textOf(block, "text", ""));
                } else if ("tool_use".equals(type)) {
                    String id = textOf(block, "id", null);
                    String name = textOf(block, "name", null);
                    if (name == null || name.isBlank()) {
                        log.warn("Anthropic 返回的 tool_use 缺少 name，已丢弃: id={}", id);
                        continue;
                    }
                    Map<String, Object> input = block.get("input") == null || block.get("input").isNull()
                        ? Map.of()
                        : mapper.convertValue(block.get("input"), Map.class);
                    toolCalls.add(new ChatMessage.ToolCall(id, name, input));
                }
            }
        }

        String content = contentBuilder.toString();

        String stopReason = textOf(root, "stop_reason", null);
        boolean finished = "end_turn".equals(stopReason) || "tool_use".equals(stopReason);

        // Token使用
        // 字段缺失时不能直接 asInt()：usage 为 JSON null 或只有部分字段时
        // NullNode.get() 返回 null → NPE，会把一次成功的回答整段丢掉
        ModelResponse.TokenUsage usage = null;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            Integer in = intOf(usageNode, "input_tokens");
            Integer out = intOf(usageNode, "output_tokens");
            if (in != null || out != null) {
                int i = in == null ? 0 : in;
                int o = out == null ? 0 : out;
                usage = new ModelResponse.TokenUsage(i, o, i + o);
            }
        }

        return new ModelResponse(content, toolCalls, finished, usage, stopReason, null);
    }

    /** 安全取字符串字段（缺失/JSON null 一律返回默认值，绝不返回字符串 "null"） */
    private static String textOf(JsonNode node, String field, String defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return defaultValue;
        }
        String s = v.asText();
        return s == null ? defaultValue : s;
    }

    /** 安全取整数字段（缺失/null/非数字一律返回 null，绝不抛异常） */
    private static Integer intOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.canConvertToInt()) {
            return null;
        }
        return v.asInt();
    }
}
