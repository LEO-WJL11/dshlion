package com.lioncode.core.plugin.tool.web;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP POST请求工具
 */
@Component
public class HttpPostTool extends AbstractToolPlugin {

    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    @Override
    public String getId() { return "tool.http.post"; }
    @Override
    public String getName() { return "http_post"; }
    @Override
    public String getDescription() { return "发送HTTP POST请求"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "url", Map.of("type", "string", "description", "请求URL"),
            "body", Map.of("type", "string", "description", "请求体"),
            "contentType", Map.of("type", "string", "description", "内容类型", "default", "application/json")
        ), "required", new String[]{"url", "body"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String url = getRequiredStringArg(arguments, "url");
            String body = getRequiredStringArg(arguments, "body");
            String contentType = getStringArg(arguments, "contentType", "application/json");
            
            RequestBody requestBody = RequestBody.create(body, MediaType.parse(contentType));
            Request request = new Request.Builder().url(url).post(requestBody).build();
            try (Response response = client.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                return success("HTTP " + response.code() + "\n" + respBody);
            }
        } catch (Exception e) {
            return error("HTTP请求失败: " + e.getMessage());
        }
    }
}
