package com.lioncode.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP协议桥接器
 * 
 * Model Context Protocol (MCP) 协议桥接，
 * 用于连接外部MCP服务器和工具。
 */
@Component
public class McpProtocolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolBridge.class);

    /** 已注册的MCP服务器 */
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();

    /**
     * 注册MCP服务器
     */
    public void registerServer(String name, String endpoint) {
        McpServer server = new McpServer(name, endpoint, McpStatus.DISCONNECTED);
        servers.put(name, server);
        log.info("MCP服务器已注册: {} ({})", name, endpoint);
    }

    /**
     * 连接MCP服务器
     */
    public boolean connect(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            log.error("未找到MCP服务器: {}", serverName);
            return false;
        }
        // 简化实现：标记为已连接
        servers.put(serverName, new McpServer(server.name(), server.endpoint(), McpStatus.CONNECTED));
        log.info("MCP服务器已连接: {}", serverName);
        return true;
    }

    /**
     * 断开MCP服务器
     */
    public void disconnect(String serverName) {
        McpServer server = servers.get(serverName);
        if (server != null) {
            servers.put(serverName, new McpServer(server.name(), server.endpoint(), McpStatus.DISCONNECTED));
            log.info("MCP服务器已断开: {}", serverName);
        }
    }

    /**
     * 获取所有MCP服务器
     */
    public Map<String, McpServer> getServers() {
        return Map.copyOf(servers);
    }

    /**
     * MCP服务器记录
     */
    public record McpServer(String name, String endpoint, McpStatus status) {}

    /**
     * MCP状态枚举
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
