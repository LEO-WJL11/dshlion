package com.lioncode.web.controller;

import com.lioncode.mcp.McpProtocolBridge;
import com.lioncode.mcp.McpServerConfig;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 服务器管理控制器
 *
 * 提供 MCP 服务器的注册、连接、断开、移除与列表查询接口。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpProtocolBridge bridge;

    public McpController(McpProtocolBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * 获取所有 MCP 服务器列表
     */
    @GetMapping
    public ApiResponse<List<McpProtocolBridge.McpServer>> listServers() {
        return ApiResponse.ok(bridge.getServers());
    }

    /**
     * 注册 MCP 服务器
     */
    @PostMapping("/servers")
    public ApiResponse<McpProtocolBridge.McpServer> registerServer(@RequestBody RegisterRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ApiResponse.error("服务器名称不能为空");
        }
        if (request.transport() == null || request.transport().isBlank()) {
            return ApiResponse.error("传输类型不能为空（stdio 或 http）");
        }
        String transport = request.transport().trim().toLowerCase();
        if (!McpServerConfig.TRANSPORT_STDIO.equals(transport)
                && !McpServerConfig.TRANSPORT_HTTP.equals(transport)) {
            return ApiResponse.error("不支持的传输类型: " + request.transport());
        }

        List<String> args = request.args() == null ? List.of() : request.args();
        McpServerConfig config = new McpServerConfig(transport, request.command(), args, request.url());
        try {
            bridge.registerServer(request.name(), config);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
        return ApiResponse.ok("服务器已注册", bridge.getServer(request.name()));
    }

    /**
     * 连接服务器并加载工具
     */
    @PostMapping("/servers/{name}/connect")
    public ApiResponse<McpProtocolBridge.McpServer> connect(@PathVariable("name") String name) {
        if (bridge.getServer(name) == null) {
            return ApiResponse.error("未找到 MCP 服务器: " + name);
        }
        boolean ok = bridge.connect(name);
        McpProtocolBridge.McpServer server = bridge.getServer(name);
        if (!ok) {
            return ApiResponse.error("连接失败", server == null ? null : server.error());
        }
        return ApiResponse.ok("连接成功，已加载 " + server.toolCount() + " 个工具", server);
    }

    /**
     * 断开服务器
     */
    @PostMapping("/servers/{name}/disconnect")
    public ApiResponse<McpProtocolBridge.McpServer> disconnect(@PathVariable("name") String name) {
        bridge.disconnect(name);
        return ApiResponse.ok("已断开连接", bridge.getServer(name));
    }

    /**
     * 移除服务器
     */
    @DeleteMapping("/servers/{name}")
    public ApiResponse<Void> removeServer(@PathVariable("name") String name) {
        bridge.removeServer(name);
        return ApiResponse.ok("服务器已移除", null);
    }

    /**
     * 注册请求体
     */
    public record RegisterRequest(
        String name,
        String transport,
        String command,
        List<String> args,
        String url
    ) {}
}
