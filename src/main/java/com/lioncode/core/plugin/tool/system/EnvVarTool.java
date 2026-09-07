package com.lioncode.core.plugin.tool.system;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 环境变量查看工具
 */
@Component
public class EnvVarTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.system.env"; }
    @Override
    public String getName() { return "get_env"; }
    @Override
    public String getDescription() { return "查看环境变量"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.OTHER; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "name", Map.of("type", "string", "description", "变量名（可选，不填返回全部）")
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String name = getStringArg(arguments, "name", null);
        if (name != null) {
            String value = System.getenv(name);
            return success(name + "=" + (value != null ? value : "（未设置）"));
        }
        StringBuilder sb = new StringBuilder();
        System.getenv().forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        return success(sb.toString());
    }
}
