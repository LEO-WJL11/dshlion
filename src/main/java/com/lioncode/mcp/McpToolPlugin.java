package com.lioncode.mcp;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;

import java.util.Map;

/**
 * MCP 动态工具插件
 *
 * 把远程 MCP 服务器暴露的每一个工具封装成本地 ToolPlugin，
 * 交由 Agent 通过 function calling 调用。execute 时向远程发送
 * tools/call，并把结果转成 ToolResult。
 */
public class McpToolPlugin extends AbstractToolPlugin {

    /** 所属 MCP 连接，用于转发 tools/call */
    private final McpConnection connection;
    /** 本地插件 id：mcp.<serverName>.<toolName> */
    private final String id;
    /** 暴露给模型的函数名（重名时带 serverName 后缀） */
    private final String name;
    /** 远程服务器上的原始工具名（tools/call 时使用） */
    private final String remoteName;
    /** 工具描述 */
    private final String description;
    /** 远程工具的 inputSchema，原样透传 */
    private final Map<String, Object> inputSchema;

    public McpToolPlugin(McpConnection connection, String id, String name, String remoteName,
                         String description, Map<String, Object> inputSchema) {
        this.connection = connection;
        this.id = id;
        this.name = name;
        this.remoteName = remoteName;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema == null
            ? Map.of("type", "object", "properties", Map.of())
            : inputSchema;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public PermissionLevel getRequiredPermission() {
        return PermissionLevel.WORKSPACE_WRITE;
    }

    /**
     * 直接透传远程工具的 inputSchema
     */
    @Override
    protected Map<String, Object> getParametersSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return connection.callTool(remoteName, arguments);
    }
}
