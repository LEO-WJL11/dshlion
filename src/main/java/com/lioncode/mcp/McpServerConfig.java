package com.lioncode.mcp;

import java.util.List;

/**
 * MCP服务器配置
 *
 * 描述一个外部 MCP 服务器的连接参数。
 * 支持两种传输方式：
 * - stdio：通过 command + args 启动子进程，走标准输入输出
 * - http：Streamable HTTP，POST 到 url
 */
public record McpServerConfig(
    /** 传输类型："stdio" 或 "http" */
    String transport,
    /** stdio 传输的启动命令 */
    String command,
    /** stdio 传输的命令参数 */
    List<String> args,
    /** http 传输的 baseUrl */
    String url
) {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";

    /** 是否为 stdio 传输 */
    public boolean isStdio() {
        return TRANSPORT_STDIO.equalsIgnoreCase(transport);
    }

    /** 是否为 http 传输 */
    public boolean isHttp() {
        return TRANSPORT_HTTP.equalsIgnoreCase(transport);
    }

    /** 返回归一化的参数列表（无 null） */
    public List<String> safeArgs() {
        return args == null ? List.of() : args;
    }
}
