package com.lioncode.core.plugin.tool.web;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Map;

/**
 * DNS查询工具
 */
@Component
public class DnsLookupTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.web.dns"; }
    @Override
    public String getName() { return "dns_lookup"; }
    @Override
    public String getDescription() { return "DNS域名解析查询"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "hostname", Map.of("type", "string", "description", "主机名")
        ), "required", new String[]{"hostname"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String hostname = getRequiredStringArg(arguments, "hostname");
            InetAddress[] addrs = InetAddress.getAllByName(hostname);
            StringBuilder sb = new StringBuilder();
            sb.append("域名: ").append(hostname).append("\n");
            for (InetAddress addr : addrs) {
                sb.append("IP: ").append(addr.getHostAddress()).append("\n");
            }
            return success(sb.toString());
        } catch (Exception e) {
            return error("DNS查询失败: " + e.getMessage());
        }
    }
}
