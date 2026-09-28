package com.lioncode.core.plugin.tool.web;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP GET请求工具
 */
@Component
public class HttpGetTool extends AbstractToolPlugin {

    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    @Override
    public String getId() { return "tool.http.get"; }
    @Override
    public String getName() { return "http_get"; }
    @Override
    public String getDescription() { return "发送HTTP GET请求"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "url", Map.of("type", "string", "description", "请求URL"),
            "timeout", Map.of("type", "integer", "description", "超时秒数", "default", 30)
        ), "required", new String[]{"url"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String url = getRequiredStringArg(arguments, "url");
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                return success("HTTP " + response.code() + "\n" + body);
            }
        } catch (Exception e) {
            return error("HTTP请求失败: " + e.getMessage());
        }
    }
}
