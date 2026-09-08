package com.lioncode.mcp;

import com.lioncode.core.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 协议桥接器（管理器）
 *
 * Model Context Protocol (MCP) 客户端，用于连接外部 MCP 服务器，
 * 把远程工具动态注册为本地 ToolPlugin 供 Agent 调用。
 *
 * 支持 stdio（LSP 风格帧）与 http（Streamable HTTP）两种传输，
 * JSON-RPC 2.0 请求-响应按 id 匹配。
 */
@Component
public class McpProtocolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolBridge.class);

    private final PluginRegistry pluginRegistry;

    /** 已注册的 MCP 连接：名称 -> 连接对象 */
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    public McpProtocolBridge(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 注册 MCP 服务器
     */
    public void registerServer(String name, McpServerConfig config) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("服务器名称不能为空");
        }
        McpConnection existing = connections.get(name);
        if (existing != null) {
            // 重注册同名服务器：先断开旧连接
            existing.disconnect();
        }
        connections.put(name, new McpConnection(name, config, pluginRegistry));
        log.info("MCP 服务器已注册: {} (transport={})", name, config.transport());
    }

    /**
     * 连接服务器并加载工具；失败时错误信息记录在状态里，不抛异常
     *
     * @return 是否连接成功
     */
    public boolean connect(String name) {
        McpConnection conn = connections.get(name);
        if (conn == null) {
            log.error("未找到 MCP 服务器: {}", name);
            return false;
        }
        return conn.connect();
    }

    /**
     * 断开服务器
     */
    public void disconnect(String name) {
        McpConnection conn = connections.get(name);
        if (conn != null) {
            conn.disconnect();
        }
    }

    /**
     * 移除服务器（先断开再移除）
     */
    public void removeServer(String name) {
        McpConnection conn = connections.remove(name);
        if (conn != null) {
            conn.disconnect();
            log.info("MCP 服务器已移除: {}", name);
        }
    }

    /**
     * 获取所有服务器状态（按名称排序）
     */
    public List<McpServer> getServers() {
        return connections.values().stream()
            .sorted(Comparator.comparing(McpConnection::getName))
            .map(c -> toServer(c))
            .toList();
    }

    /**
     * 获取单个服务器状态
     */
    public McpServer getServer(String name) {
        McpConnection c = connections.get(name);
        return c == null ? null : toServer(c);
    }

    private McpServer toServer(McpConnection c) {
        return new McpServer(c.getName(), c.getConfig().transport(), c.getStatus(),
            c.getToolCount(), c.getError());
    }

    /**
     * MCP 服务器状态记录
     */
    public record McpServer(
        /** 服务器名称 */
        String name,
        /** 传输类型：stdio / http */
        String transport,
        /** 连接状态 */
        McpStatus status,
        /** 已加载工具数 */
        int toolCount,
        /** 错误信息（失败时） */
        String error
    ) {}

    /**
     * MCP 状态枚举
     */
    public enum McpStatus {
        DISCONNECTED("未连接"),
        CONNECTING("连接中"),
        CONNECTED("已连接"),
        ERROR("错误");

        private final String displayName;
        McpStatus(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }
}
