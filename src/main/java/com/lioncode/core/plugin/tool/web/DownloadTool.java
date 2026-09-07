package com.lioncode.core.plugin.tool.web;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 文件下载工具
 */
@Component
public class DownloadTool extends AbstractToolPlugin {

    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();

    @Override
    public String getId() { return "tool.web.download"; }
    @Override
    public String getName() { return "download_file"; }
    @Override
    public String getDescription() { return "从URL下载文件"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "url", Map.of("type", "string", "description", "下载URL"),
            "savePath", Map.of("type", "string", "description", "保存路径")
        ), "required", new String[]{"url", "savePath"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String url = getRequiredStringArg(arguments, "url");
            String savePath = resolvePath(getRequiredStringArg(arguments, "savePath"));
            
            Request request = new Request.Builder().url(url).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return error("下载失败: HTTP " + response.code());
                byte[] bytes = response.body().bytes();
                Path path = Path.of(savePath);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.write(path, bytes);
                return success("文件已下载: " + savePath + " (" + bytes.length + "字节)");
            }
        } catch (Exception e) {
            return error("下载失败: " + e.getMessage());
        }
    }
}
