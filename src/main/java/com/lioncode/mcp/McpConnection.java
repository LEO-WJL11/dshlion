package com.lioncode.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lioncode.core.plugin.Plugin;
import com.lioncode.core.plugin.PluginRegistry;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 服务器连接
 *
 * 维护单个 MCP 服务器的生命周期：建立传输、执行 initialize 握手、
 * 发送 initialized 通知、tools/list 加载工具并注册为本地插件、
 * 转发 tools/call 调用。
 *
 * connect() 失败时把错误记录到状态里，不抛异常到调用方（控制器）。
 */
public class McpConnection {

    private static final Logger log = LoggerFactory.getLogger(McpConnection.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String CLIENT_NAME = "Lion-Code";
    private static final String CLIENT_VERSION = "1.0.0";

    /** 握手（initialize / tools/list）超时：20 秒 */
    private static final long HANDSHAKE_TIMEOUT_MS = 20_000;
    /** 工具调用（tools/call）超时：120 秒 */
    private static final long TOOL_TIMEOUT_MS = 120_000;

    private final String name;
    private final McpServerConfig config;
    private final PluginRegistry pluginRegistry;
    private final AtomicLong idGen = new AtomicLong(0);

    private volatile McpProtocolBridge.McpStatus status = McpProtocolBridge.McpStatus.DISCONNECTED;
    private volatile String error;
    private volatile McpTransport transport;

    /** 本次连接已注册的插件 id，断开时统一注销 */
    private final List<String> pluginIds = new CopyOnWriteArrayList<>();

    public McpConnection(String name, McpServerConfig config, PluginRegistry pluginRegistry) {
        this.name = name;
        this.config = config;
        this.pluginRegistry = pluginRegistry;
    }

    public String getName() {
        return name;
    }

    public McpServerConfig getConfig() {
        return config;
    }

    public McpProtocolBridge.McpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public int getToolCount() {
        return pluginIds.size();
    }

    /**
     * 建立连接并加载工具；失败时记录错误状态并返回 false，不抛异常
     */
    public synchronized boolean connect() {
        if (status == McpProtocolBridge.McpStatus.CONNECTED) {
            log.warn("[{}] 服务器已连接", name);
            return true;
        }
        setStatus(McpProtocolBridge.McpStatus.CONNECTING, null);
        try {
            transport = createTransport();
            transport.start();

            // 1) initialize 握手
            ObjectNode initParams = mapper.createObjectNode();
            initParams.put("protocolVersion", PROTOCOL_VERSION);
            initParams.set("capabilities", mapper.createObjectNode());
            ObjectNode clientInfo = initParams.putObject("clientInfo");
            clientInfo.put("name", CLIENT_NAME);
            clientInfo.put("version", CLIENT_VERSION);

            JsonNode initResult = sendRequest("initialize", initParams, HANDSHAKE_TIMEOUT_MS);
            log.info("[{}] MCP initialize 成功: serverInfo={}", name, initResult);

            // 2) initialized 通知
            sendNotification("notifications/initialized");

            // 3) tools/list 拉取工具列表
            JsonNode listResult = sendRequest("tools/list", mapper.createObjectNode(),
                HANDSHAKE_TIMEOUT_MS);
            int count = registerTools(listResult);

            setStatus(McpProtocolBridge.McpStatus.CONNECTED, null);
            log.info("[{}] MCP 连接成功，加载 {} 个工具", name, count);
            return true;
        } catch (Exception e) {
            log.error("[{}] MCP 连接失败", name, e);
            setStatus(McpProtocolBridge.McpStatus.ERROR, e.getMessage());
            closeTransportQuietly();
            return false;
        }
    }

    /**
     * 断开连接：关闭传输并注销本次加载的插件
     */
    public synchronized void disconnect() {
        closeTransportQuietly();
        unregisterPlugins();
        setStatus(McpProtocolBridge.McpStatus.DISCONNECTED, null);
        log.info("[{}] MCP 已断开", name);
    }

    /**
     * 转发一个 tools/call 调用，把远程结果转成 ToolResult
     */
    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));

            JsonNode result = sendRequest("tools/call", params, TOOL_TIMEOUT_MS);
            boolean isError = result != null && result.has("isError")
                && result.get("isError").asBoolean();
            String content = joinContent(result);

            if (isError) {
                return ToolResult.error(content.isEmpty() ? "远程工具返回错误" : content);
            }
            return ToolResult.success(content);
        } catch (Exception e) {
            log.error("[{}] 调用工具 {} 失败", name, toolName, e);
            return ToolResult.error("MCP 工具调用失败: " + e.getMessage());
        }
    }

    /**
     * 根据配置创建对应传输
     */
    private McpTransport createTransport() {
        if (config.isStdio()) {
            if (config.command() == null || config.command().isBlank()) {
                throw new IllegalArgumentException("stdio 传输需要 command");
            }
            return new McpStdioTransport(name, config);
        }
        if (config.isHttp()) {
            if (config.url() == null || config.url().isBlank()) {
                throw new IllegalArgumentException("http 传输需要 url");
            }
            return new McpHttpTransport(name, config);
        }
        throw new IllegalArgumentException("不支持的传输类型: " + config.transport());
    }

    /**
     * 发送 JSON-RPC 请求并返回 result 节点（含错误处理）
     */
    private JsonNode sendRequest(String method, JsonNode params, long timeoutMillis) throws Exception {
        long id = idGen.incrementAndGet();
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        JsonNode response = transport.sendRequest(request, timeoutMillis);
        if (response == null) {
            throw new RuntimeException("MCP 无响应: " + method);
        }
        if (response.has("error")) {
            JsonNode err = response.get("error");
            String msg = err.has("message") ? err.get("message").asText() : err.toString();
            throw new RuntimeException("MCP 调用失败(" + method + "): " + msg);
        }
        return response.get("result");
    }

    /**
     * 发送 JSON-RPC 通知（无 id）
     */
    private void sendNotification(String method) throws Exception {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        transport.sendNotification(notification);
    }

    /**
     * 把 tools/list 结果中的每个工具注册为本地动态插件
     */
    private int registerTools(JsonNode listResult) {
        JsonNode tools = listResult == null ? null : listResult.get("tools");
        if (tools == null || !tools.isArray()) {
            log.warn("[{}] tools/list 结果为空", name);
            return 0;
        }

        // 收集当前已占用插件名，用于重名检测
        Set<String> usedNames = new HashSet<>();
        for (Plugin p : pluginRegistry.getAllPlugins()) {
            usedNames.add(p.getName());
        }

        int count = 0;
        for (JsonNode tool : tools) {
            String toolName = tool.has("name") ? tool.get("name").asText() : null;
            if (toolName == null || toolName.isBlank()) {
                log.warn("[{}] 跳过无名工具: {}", name, tool);
                continue;
            }
            String description = tool.has("description") ? tool.get("description").asText() : "";
            Map<String, Object> inputSchema = tool.has("inputSchema") && tool.get("inputSchema").isObject()
                ? mapper.convertValue(tool.get("inputSchema"), new TypeReference<Map<String, Object>>() {})
                : Map.of("type", "object", "properties", Map.of());

            String pluginName = resolveName(toolName, usedNames);
            usedNames.add(pluginName);
            String pluginId = "mcp." + name + "." + toolName;

            McpToolPlugin plugin = new McpToolPlugin(this, pluginId, pluginName, toolName,
                description, inputSchema);
            pluginRegistry.register(plugin);
            pluginIds.add(plugin.getId());
            count++;
        }
        return count;
    }

    /**
     * 解析本地插件名：远程工具名重名时追加 serverName 后缀
     */
    private String resolveName(String toolName, Set<String> usedNames) {
        if (!usedNames.contains(toolName)) {
            return toolName;
        }
        String base = toolName + "_" + name;
        String candidate = base;
        int i = 2;
        while (usedNames.contains(candidate)) {
            candidate = base + "_" + i++;
        }
        return candidate;
    }

    /**
     * 注销本次连接注册的所有插件
     */
    private void unregisterPlugins() {
        for (String id : pluginIds) {
            try {
                pluginRegistry.unregister(id);
            } catch (Exception e) {
                log.warn("[{}] 注销插件失败: {}", name, id, e);
            }
        }
        pluginIds.clear();
    }

    /**
     * 拼接 tools/call 响应的 content 数组：
     * type=="text" 取 text 字段，其余项转 JSON 附带
     */
    private String joinContent(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = result == null ? null : result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText();
                if ("text".equals(type)) {
                    sb.append(item.path("text").asText());
                } else {
                    sb.append("\n[").append(type).append("] ").append(item.toString());
                }
            }
        }
        return sb.toString();
    }

    private void setStatus(McpProtocolBridge.McpStatus status, String error) {
        this.status = status;
        this.error = error;
    }

    private void closeTransportQuietly() {
        McpTransport t = transport;
        transport = null;
        if (t != null) {
            try {
                t.close();
            } catch (Exception e) {
                log.warn("[{}] 关闭传输异常", name, e);
            }
        }
    }
}
