package com.lioncode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 传输层抽象
 *
 * 屏蔽 stdio / http 两种传输方式的差异，向上层提供统一的
 * 请求-响应（按 id 匹配）与单向通知两种原语。
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 建立传输连接（stdio 启动子进程；http 无状态，可空实现）
     */
    void start() throws Exception;

    /**
     * 发送一个 JSON-RPC 请求并等待响应
     *
     * @param request      完整的 JSON-RPC 请求节点（含 jsonrpc/id/method/params）
     * @param timeoutMillis 等待响应的超时毫秒数
     * @return 完整的 JSON-RPC 响应节点（含 id + result/error）
     */
    JsonNode sendRequest(JsonNode request, long timeoutMillis) throws Exception;

    /**
     * 发送一个 JSON-RPC 通知（无 id，不等待响应）
     */
    void sendNotification(JsonNode notification) throws Exception;

    /**
     * 关闭传输，释放子进程/连接等资源
     */
    @Override
    void close();
}
