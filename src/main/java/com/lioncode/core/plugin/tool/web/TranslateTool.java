package com.lioncode.core.plugin.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 翻译：**不需要任何密钥**。
 *
 * <p>以前这里是个占位实现，只会回一句"需要配置翻译API密钥"，等于不能用。
 * 现在走 MyMemory 的公开接口（免费、不用注册、不用 key）；一次太长就分段翻，
 * 再拼回去。真翻不动时如实说明原因，而不是假装成功。
 */
@Component
public class TranslateTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(TranslateTool.class);

    /** 免费接口单次大概 500 字符，留点余量分段 */
    private static final int CHUNK = 450;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build();

    @Override
    public String getId() { return "tool.web.translate"; }

    @Override
    public String getName() { return "translate"; }

    @Override
    public String getDescription() { return "文本翻译（免密钥，走公开接口；也可用它把中文译成英文再搜）"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string", "description", "待翻译文本"));
        props.put("from", Map.of("type", "string", "description", "源语言，默认 auto", "default", "auto"));
        props.put("to", Map.of("type", "string", "description", "目标语言，默认 zh", "default", "zh"));
        return Map.of("type", "object", "properties", props, "required", new String[] {"text"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String text;
        try {
            text = getRequiredStringArg(arguments, "text");
        } catch (Exception e) {
            return error(e.getMessage());
        }
        String to = getStringArg(arguments, "to", "zh");
        String from = getStringArg(arguments, "from", "auto");
        if (to == null || to.isBlank()) {
            to = "zh";
        }
        if (from == null || from.isBlank() || from.equalsIgnoreCase("auto")) {
            from = guessSource(text, to);
        }

        StringBuilder out = new StringBuilder();
        int done = 0;
        for (String chunk : split(text, CHUNK)) {
            String piece = translateChunk(chunk, from, to);
            if (piece == null) {
                if (out.length() == 0) {
                    return error("翻译没成功（免费接口没响应或超时）。可以先试短一点的一段，"
                        + "或直接把原文交给模型自己翻。原文长度 " + text.length() + " 字符。");
                }
                break;      // 部分成功：把已经翻好的给出去，并标注
            }
            out.append(piece);
            done++;
        }
        if (out.length() == 0) {
            return error("翻译没成功（没拿到内容）。");
        }
        String tail = done * CHUNK < text.length() ? "\n（注：只翻好了前面一部分，原文较长）" : "";
        return success(from + " → " + to + "：\n" + out + tail);
    }

    /** 交给免费接口翻一段；失败返回 null */
    private String translateChunk(String chunk, String from, String to) {
        try {
            String url = "https://api.mymemory.translated.net/get?q="
                + URLEncoder.encode(chunk, StandardCharsets.UTF_8)
                + "&langpair=" + URLEncoder.encode(from + "|" + to, StandardCharsets.UTF_8);
            Request req = new Request.Builder().url(url).get()
                .header("User-Agent", "LionBox/1.3 (translate tool)").build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("翻译接口 HTTP {}", resp.code());
                    return null;
                }
                String body = resp.body().string();
                JsonNode node = MAPPER.readTree(body);
                JsonNode data = node.path("responseData").path("translatedText");
                if (data.isMissingNode() || data.asText().isBlank()) {
                    log.warn("翻译接口没给出译文: {}", body.length() > 200 ? body.substring(0, 200) : body);
                    return null;
                }
                return data.asText();
            }
        } catch (Exception e) {
            log.warn("翻译失败: {}", e.getMessage());
            return null;
        }
    }

    /** 目标语言不是中文、原文里又有中文 → 当成 zh；否则按英中互相猜一下 */
    private String guessSource(String text, String to) {
        boolean hasCjk = text.codePoints().anyMatch(cp ->
            (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF));
        if (hasCjk) {
            return "zh-CN";
        }
        return to.startsWith("zh") ? "en" : "en";
    }

    /** 按段落/句子切块，尽量不把一句话切断 */
    private java.util.List<String> split(String text, int size) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (text.length() <= size) {
            parts.add(text);
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int cut = Math.max(text.lastIndexOf('\n', end), Math.max(
                    text.lastIndexOf('\u3002', end), Math.max(
                    text.lastIndexOf('.', end), text.lastIndexOf('\uff01', end))));
                if (cut > start + size / 2) {
                    end = cut + 1;
                }
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }
}
