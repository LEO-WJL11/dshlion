package com.lioncode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MCP Streamable HTTP 传输
 *
 * JSON-RPC POST 到 baseUrl（若不以 /mcp 结尾则拼接 /mcp）。
 * 响应可能是直接 JSON，也可能是 SSE（text/event-stream，每行 event:/data:），
 * 解析 data 行的 JSON 作为响应。
 */
public class McpHttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String ACCEPT = "application/json, text/event-stream";

    private final String serverName;
    private final McpServerConfig config;
    private final String endpoint;
    private final OkHttpClient client;

    public McpHttpTransport(String serverName, McpServerConfig config) {
        this.serverName = serverName;
        this.config = config;
        this.endpoint = normalizeUrl(config.url());
        this.client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
        log.info("[{}] MCP http 端点: {}", serverName, endpoint);
    }

    /**
     * 归一化 URL：去尾斜杠，若不以 /mcp 结尾则拼接 /mcp
     */
    private static String normalizeUrl(String url) {
        String base = url == null ? "" : url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.endsWith("/mcp")) {
            base = base + "/mcp";
        }
        return base;
    }

    @Override
    public void start() throws Exception {
        // HTTP 无状态，无需额外初始化
    }

    @Override
    public JsonNode sendRequest(JsonNode request, long timeoutMillis) throws Exception {
        String body = mapper.writeValueAsString(request);
        Request httpRequest = new Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", ACCEPT)
            .post(RequestBody.create(body, JSON_TYPE))
            .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "无响应体";
                throw new RuntimeException("MCP HTTP请求失败: HTTP " + response.code() + " - " + errBody);
            }
            String respBody = response.body() != null ? response.body().string() : "";
            String contentType = response.header("Content-Type");
            if (contentType != null && contentType.contains("text/event-stream")) {
                return parseSse(respBody);
            }
            return mapper.readTree(respBody);
        }
    }

    @Override
    public void sendNotification(JsonNode notification) throws Exception {
        String body = mapper.writeValueAsString(notification);
        Request httpRequest = new Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", ACCEPT)
            .post(RequestBody.create(body, JSON_TYPE))
            .build();
        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                log.debug("[{}] 发送通知失败: HTTP {}", serverName, response.code());
            }
        }
    }

    /**
     * 解析 SSE 响应：收集 data 行的 JSON，优先返回带 id 的响应
     */
    private JsonNode parseSse(String body) throws Exception {
        String[] lines = body.split("\\r\\n|\\n|\\r");
        List<JsonNode> candidates = new ArrayList<>();
        StringBuilder data = new StringBuilder();

        for (String line : lines) {
            if (line.isEmpty()) {
                // 事件边界
                if (data.length() > 0) {
                    addCandidate(data.toString(), candidates);
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String d = line.substring("data:".length());
                if (d.startsWith(" ")) {
                    d = d.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(d);
            }
            // event:/id:/retry: 行忽略
        }
        // 末尾未以空行结束的事件
        if (data.length() > 0) {
            addCandidate(data.toString(), candidates);
        }

        if (candidates.isEmpty()) {
            throw new RuntimeException("MCP SSE 响应未包含有效 data: " + body);
        }
        // 优先返回带 id 的 JSON-RPC 响应
        for (JsonNode c : candidates) {
            if (c.has("id")) {
                return c;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * 把一段 data 载荷解析为 JSON 候选节点
     */
    private void addCandidate(String data, List<JsonNode> candidates) {
        String trimmed = data.trim();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
            return;
        }
        try {
            candidates.add(mapper.readTree(trimmed));
        } catch (Exception e) {
            log.debug("[{}] SSE data 解析失败: {}", serverName, trimmed);
        }
    }

    @Override
    public void close() {
        // OkHttpClient 连接池无需显式关闭，保持可复用
        log.debug("[{}] MCP http 传输已关闭", serverName);
    }
}
