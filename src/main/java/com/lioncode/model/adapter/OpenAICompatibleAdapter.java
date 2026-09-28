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
 * 本产品为硬件一体机（模型盒子），模型运行时随盒子交付并绑定回环地址，
 * 因此适配器**只**对接盒子本地端点（如 llama.cpp / LM-Studio 等GGUF推理服务），
 * 不再包含任何云端服务商地址。
 * 
 * 特性：
 * - 同步/流式调用
 * - 工具调用（function calling）
 * - 思考等级参数（本地GGUF服务不传递）
 * - 自动获取模型列表
 * - API-Key 可选：本地运行时无需鉴权，留空即不发送 Authorization 头
 */
@Component
public class OpenAICompatibleAdapter implements ModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleAdapter.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper mapper = new ObjectMapper();

    private String baseUrl = "";
    private String apiKey = "";
    private final OkHttpClient httpClient;

    /**
     * 盒子本地模型运行时。
     * 用来实现"启动不加载模型、第一条消息才加载"：
     * 发请求之前先看端点是不是由它托管，是就确保进程已经起来。
     * 用户填自己的 API 时 manages() 返回 false，永远不碰本地运行时。
     */
    private final com.lioncode.model.runtime.LocalModelRuntime localRuntime;

    public OpenAICompatibleAdapter(com.lioncode.model.runtime.LocalModelRuntime localRuntime) {
        this.localRuntime = localRuntime;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 读超时给 15 分钟：同步（非流式）路径要等服务端把整段回答生成完才开始收字节，
            // 本地模型 256K 上下文 + 最多 4096 token 输出，慢的时候要好几分钟。
            .readTimeout(900, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 发请求前的惰性加载钩子。
     *
     * 这是整个惰性加载唯一真正需要拦截的地方：不管调用来自同步聊天、流式聊天
     * 还是会话标题生成，都必然经过这里。
     */
    private void ensureEndpointReady() {
        if (localRuntime == null || !localRuntime.manages(baseUrl)) {
            return;   // 用户用的是自己的 API，本地模型不参与
        }
        localRuntime.ensureRunning();
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
        // 换了端点就忘掉上一次的"拒绝 tools"结论：新端点可能完全支持原生调用
        toolsRejected = false;
        log.info("本地模型适配器配置已更新: baseUrl={}", baseUrl);
    }

    @Override
    public boolean isAvailable() {
        // 本地运行时无需API-Key：只校验端点是否已配置
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * 鉴权头取值
     * 
     * 盒子本地运行时不需要API-Key，此处返回null表示不发送 Authorization 头；
     * 若运行时确实要求非空头（部分实现会校验），可填入任意占位值（如 local）。
     */
    private String authToken() {
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : null;
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                              List<Map<String, Object>> tools) {
        return chatInternal(messages, model, thinkingLevel, tools, null, null);
    }

    @Override
    public ModelResponse chatWithOptions(List<ChatMessage> messages, String model,
                                         ThinkingLevel thinkingLevel, List<Map<String, Object>> tools,
                                         Map<String, Object> extraBody, Integer maxTokens) {
        return chatInternal(messages, model, thinkingLevel, tools, extraBody, maxTokens);
    }

    /**
     * 实际执行同步chat调用
     *
     * 三层降级重试，都是为了让「用户自己填的 API」尽量能用起来：
     *   1. API 不认 tool_choice / parallel_tool_calls → 去掉这两个参数重试（工具定义留着）
     *   2. API 拒绝整个工具定义（HTTP 400）→ 去掉工具重试，Agent 退化成纯文本工具调用
     *   3. API 不认识某个可选参数（reasoning_effort / max_tokens）→ 去掉可选参数重试
     * 各家 OpenAI 兼容服务对可选字段的容忍度差别很大，例如
     * OpenAI 的 gpt-4o-mini 收到 reasoning_effort 会直接 400。
     */
    private ModelResponse chatInternal(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                       List<Map<String, Object>> tools,
                                       Map<String, Object> extraBody, Integer maxTokens) {
        RuntimeException last;
        String msg;

        // 第 1 层：完整请求（工具定义 + tool_choice/parallel_tool_calls）
        try {
            return doChat(messages, model, thinkingLevel, tools, extraBody, maxTokens, true);
        } catch (RuntimeException e) {
            last = e;
            msg = e.getMessage() == null ? "" : e.getMessage();
        }

        boolean hasTools = tools != null && !tools.isEmpty();

        // 第 2 层：不认工具配套参数 → 去掉 tool_choice / parallel_tool_calls，工具定义保留
        if (hasTools && msg.contains("HTTP 400")) {
            log.warn("接口不接受 tool_choice/parallel_tool_calls，去掉后重试: {}", msg);
            try {
                return doChat(messages, model, thinkingLevel, tools, extraBody, maxTokens, false);
            } catch (RuntimeException e) {
                last = e;
                msg = e.getMessage() == null ? "" : e.getMessage();
            }
        }

        // 第 3 层：整个工具定义被拒 → 去掉工具，降级成文本 <tool_call> 约定
        if (hasTools && msg.contains("HTTP 400")) {
            log.warn("携带工具定义调用失败，去掉工具重试（降级为XML/JSON文本工具调用）: {}", msg);
            toolsRejected = true;   // 记下来：下一轮提示词改回文本格式教学
            try {
                return doChat(messages, model, null, List.of(), extraBody, maxTokens, false);
            } catch (RuntimeException e) {
                last = e;
                msg = e.getMessage() == null ? "" : e.getMessage();
            }
        }

        // 第 4 层：可选参数被拒 → 可选参数一起去掉
        boolean optionalParamRejected = msg.contains("HTTP 400")
            && (msg.contains("reasoning_effort") || msg.contains("max_tokens")
                || msg.contains("thinking") || msg.contains("Unsupported parameter")
                || msg.contains("Invalid request parameters")
                || msg.contains("tool_choice") || msg.contains("parallel_tool_calls"));
        if (optionalParamRejected) {
            log.warn("接口不接受可选参数，去掉后重试: {}", msg);
            try {
                return doChat(messages, model, null, tools, null, null, false);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    /** 真正发一次请求 */
    private ModelResponse doChat(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                 List<Map<String, Object>> tools,
                                 Map<String, Object> extraBody, Integer maxTokens,
                                 boolean strictToolParams) {
        try {
            ensureEndpointReady();      // 本地模式：第一条消息时才真正加载模型
            ObjectNode request = buildRequest(messages, model, thinkingLevel, false, tools, strictToolParams);
            if (maxTokens != null && maxTokens > 0) {
                request.put("max_tokens", maxTokens);
            }
            if (extraBody != null) {
                for (Map.Entry<String, Object> e : extraBody.entrySet()) {
                    request.set(e.getKey(), mapper.valueToTree(e.getValue()));
                }
            }
            String jsonBody = mapper.writeValueAsString(request);
            
            Request.Builder builder = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE));
            // 本地运行时无需鉴权：未配置API-Key时不发送 Authorization 头
            if (authToken() != null) {
                builder.addHeader("Authorization", "Bearer " + authToken());
            }
            Request httpRequest = builder.build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    throw new RuntimeException("模型调用失败: HTTP " + response.code() + " - " + errorBody);
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI兼容接口调用失败", e);
            throw new RuntimeException("模型调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ModelChunk> chatStream(List<ChatMessage> messages, String model, ThinkingLevel thinkingLevel,
                                       List<Map<String, Object>> tools) {
        return Flux.create(sink -> startStream(sink, messages, model, thinkingLevel, tools, 0));
    }

    /**
     * 启动流式请求；被 API 拒绝时逐级降级重试：
     *   stage 0 → 完整请求（工具定义 + tool_choice/parallel_tool_calls）
     *   stage 1 → 去掉 tool_choice / parallel_tool_calls
     *   stage 2 → 去掉工具定义（退化成文本 &lt;tool_call&gt; 约定）
     */
    private void startStream(reactor.core.publisher.FluxSink<ModelChunk> sink, List<ChatMessage> messages,
                             String model, ThinkingLevel thinkingLevel, List<Map<String, Object>> tools,
                             int stage) {
        boolean hasTools = tools != null && !tools.isEmpty();
        // stage 越高，请求越"朴素"
        List<Map<String, Object>> effectiveTools = stage >= 2 ? List.of() : tools;
        ThinkingLevel effectiveThinking = stage >= 1 ? null : thinkingLevel;
        boolean strictToolParams = stage == 0;
        try {
            ensureEndpointReady();      // 本地模式：第一条消息时才真正加载模型
            ObjectNode request = buildRequest(messages, model, effectiveThinking, true,
                effectiveTools, strictToolParams);
            String jsonBody = mapper.writeValueAsString(request);

            Request.Builder builder = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE));
            // 本地运行时无需鉴权：未配置API-Key时不发送 Authorization 头
            if (authToken() != null) {
                builder.addHeader("Authorization", "Bearer " + authToken());
            }
            Request httpRequest = builder.build();

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
                    // 安全网：HTTP 400 时逐级降级（先去工具参数，再去工具定义）
                    if (response != null && response.code() == 400 && stage < 2 && hasTools) {
                        log.warn("流式请求被拒(HTTP 400)，降级到 stage {} 重试: {}",
                            stage + 1, response.message());
                        startStream(sink, messages, model, thinkingLevel, tools, stage + 1);
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
            Request.Builder builder = new Request.Builder()
                .url(baseUrl + "/models")
                .get();
            // 本地运行时无需鉴权：未配置API-Key时不发送 Authorization 头
            if (authToken() != null) {
                builder.addHeader("Authorization", "Bearer " + authToken());
            }
            Request request = builder.build();

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
                                      List<Map<String, Object>> tools,
                                      boolean strictToolParams) {
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
            // 原生 function calling 的两个配套参数（可由 strictToolParams 关掉）：
            //   tool_choice=auto          —— 有的服务不会默认走工具通道，显式声明更稳
            //   parallel_tool_calls=false —— 线上契约是「一轮只执行一个工具」，
            //                                直接在服务端禁止并行，省得回了多个还要丢弃
            if (strictToolParams) {
                request.put("tool_choice", "auto");
                request.put("parallel_tool_calls", false);
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
     * 本地端点也走**原生 function calling**。
     *
     * 这里的结论是实测出来的，别再改回"本地走文本"：
     *   1. 随盒子的 llama-server 会按模型的 chat 模板解析工具调用 —— 带上 tools 时，
     *      模型吐的模板原生语法 <tool_call><function=名字>…</function></tool_call>
     *      会被服务端解析成**标准 tool_calls**（流式也正常）；
     *   2. 反过来不下发 tools 时，模型会开始**编造工具**
     *      （实测回复"我能用的工具包括 web_search、image_search…但没有目录浏览能力"），
     *      因为它看不到真实工具清单；
     *   3. 文本 <tool_call> 解析器一直在线，所以万一某个端点的模板不认 tools，
     *      模型退回吐文本也照样能跑；接口直接 400 的话还有 toolsRejected 自动降级。
     */
    @Override
    public boolean prefersTextToolCalls() {
        return false;
    }

    /** 端点是否拒过 tools 定义（一旦拒过就一直走文本，直到端点配置变更） */
    private volatile boolean toolsRejected = false;

    @Override
    public boolean toolDefinitionsRejected() {
        return toolsRejected;
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
        
        String content = textOf(message, "content", "");
        String finishReason = textOf(choice, "finish_reason", null);

        // 思考内容：DeepSeek等thinking模式要求原样回传
        String reasoningContent = textOf(message, "reasoning_content", null);

        // 解析工具调用（校验name有效性）
        // malformed=true 表示「模型确实想做工具调用，但那条调用是残缺的」——
        // AgentLoop 据此纠正重试，而不是当成"模型正常收尾"把任务空着结束。
        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        boolean malformedToolCall = false;
        if (message.has("tool_calls")) {
            for (JsonNode tc : message.get("tool_calls")) {
                String id = textOf(tc, "id", null);
                if (id == null || id.isBlank()) {
                    id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                }
                String name = null;
                String argsStr = "{}";
                JsonNode func = tc.get("function");
                if (func != null && !func.isNull()) {
                    name = textOf(func, "name", null);
                    argsStr = textOf(func, "arguments", "{}");
                }
                // name 为空：无法执行，标记为残缺
                if (name == null || name.isBlank()) {
                    log.warn("API返回的工具调用缺少name（残缺调用）: id={}, arguments={}", id, argsStr);
                    malformedToolCall = true;
                    continue;
                }
                Map<String, Object> args;
                try {
                    args = mapper.readValue(argsStr, Map.class);
                } catch (Exception e) {
                    // 参数不是合法 JSON：不能执行，但也不该让整次模型调用失败
                    log.warn("工具调用参数不是合法JSON（残缺调用）: name={}, arguments={}", name, argsStr);
                    malformedToolCall = true;
                    continue;
                }
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

        return new ModelResponse(content, toolCalls, true, usage, finishReason, reasoningContent,
            malformedToolCall);
    }

    /**
     * 安全取字符串字段。
     *
     * 必须自己判 null：Jackson 的 NullNode.asText() 返回的是**字符串 "null"**，
     * 而多数服务商（实测 MiMo 每一片 delta 都这样）会把 content / name / id
     * 显式写成 JSON null。不判的话，模型正文会变成 "nullnullnullnull…"。
     */
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
        if (delta == null || delta.isNull()) {
            return new ModelChunk("", List.of(), false, null, null);
        }

        // 【坑】这里不能用 has() + asText()：JSON null 会被转成字符串 "null"
        String content = textOf(delta, "content", "");
        String finishReason = textOf(choice, "finish_reason", null);

        // 思考内容增量（thinking模式）
        String reasoningDelta = textOf(delta, "reasoning_content", null);

        // 解析工具调用增量（null 安全：name/id 只在第一个分片里有值）
        List<ModelChunk.ToolCallDelta> toolCallDeltas = new ArrayList<>();
        JsonNode tcs = delta.get("tool_calls");
        if (tcs != null && tcs.isArray()) {
            for (JsonNode tc : tcs) {
                int index = tc.has("index") ? tc.get("index").asInt() : 0;
                String id = textOf(tc, "id", null);
                String nameDelta = null;
                String argsDelta = null;
                JsonNode func = tc.get("function");
                if (func != null && !func.isNull()) {
                    nameDelta = textOf(func, "name", null);
                    if (nameDelta != null && nameDelta.isBlank()) {
                        nameDelta = null;
                    }
                    argsDelta = textOf(func, "arguments", null);
                }
                if (log.isDebugEnabled()) {
                    log.debug("工具调用分片: index={}, id={}, name={}, argsLen={}",
                        index, id, nameDelta, argsDelta == null ? 0 : argsDelta.length());
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
            boolean supportsThinking = supportsThinking();
            List<ModelInfo.ThinkingLevelOption> thinkingLevels = getThinkingLevels();
            
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
     * 
     * 盒子本地运行时（GGUF）不提供云端厂商的思考等级参数，统一返回false。
     */
    private boolean supportsThinking() {
        return false;
    }

    /**
     * 根据模型ID获取支持的思考等级列表
     * 
     * 本地模型不区分思考等级，返回空列表（前端隐藏该选项）。
     */
    private List<ModelInfo.ThinkingLevelOption> getThinkingLevels() {
        return List.of();
    }
}
