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
 * URL内容抓取工具
 */
@Component
public class UrlFetchTool extends AbstractToolPlugin {

    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    @Override
    public String getId() { return "tool.web.fetch"; }
    @Override
    public String getName() { return "fetch_url"; }
    @Override
    public String getDescription() { return "抓取URL内容"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "url", Map.of("type", "string", "description", "URL"),
            "maxLength", Map.of("type", "integer", "description", "最大内容长度", "default", 10000)
        ), "required", new String[]{"url"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String url = getRequiredStringArg(arguments, "url");
            int maxLength = getIntArg(arguments, "maxLength", 10000);
            
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (body.length() > maxLength) body = body.substring(0, maxLength) + "\n...(截断)";
                return success("HTTP " + response.code() + "\n" + body);
            }
        } catch (Exception e) {
            return error("抓取失败: " + e.getMessage());
        }
    }
}
