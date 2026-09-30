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

    // 【实测】原来 8s 连接 / 15s 读、而且**不带 User-Agent**：不少站点对没有 UA 的请求
    // 直接不响应（连接挂着直到超时）—— 用户那一跑就是 http_get 能用、fetch_url 超时。
    // 现在带浏览器 UA、超时放宽，失败还重试一次。
    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build();

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
            
            Request request = new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get().build();

            Exception last = null;
            for (int attempt = 1; attempt <= 2; attempt++) {   // 失败重试一次（网络抖动很常见）
                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (body.length() > maxLength) {
                        body = body.substring(0, maxLength) + "\n...(截断)";
                    }
                    String head = "HTTP " + response.code() + "（第 " + attempt + " 次尝试）\n";
                    if (!response.isSuccessful()) {
                        head += "（非 2xx：站点可能要求登录/被墙/需要换 UA）\n";
                    }
                    return success(head + body);
                } catch (Exception e) {
                    last = e;
                }
            }
            return error("抓取失败（重试过 1 次）: " + (last == null ? "未知原因" : last.getMessage())
                + "\n可以改用 http_get（同样的 GET，超时设置不同）或 web_search 搜这个地址。");
        } catch (Exception e) {
            return error("抓取失败: " + e.getMessage());
        }
    }
}
