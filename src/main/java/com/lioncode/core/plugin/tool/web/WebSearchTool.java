package com.lioncode.core.plugin.tool.web;

import com.lioncode.core.agent.AgentMode;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络搜索：**用无头浏览器搜，不要 API**。
 *
 * <p>以前这个工具是个占位实现（回一句"需要配置搜索 API 密钥"，等于不能用）。
 * 现在用系统里已经装好的 Edge / Chrome 打开搜索结果页，把渲染后的 DOM 抓回来自己解析：
 * 不需要任何密钥、注册或配额，也不用管哪家 API 哪天改条款。
 *
 * <p>两条腿走路：
 * <ol>
 *   <li>首选无头浏览器（{@link HeadlessBrowser}，用 --dump-dom 拿渲染后的 DOM）；</li>
 *   <li>浏览器没装/打不开时，退到直接用 HTTP 抓同一个结果页（Bing 的结果页是服务端渲染的，
 *       直接抓也拿得到），并在结果里**如实说明**走的哪条路。</li>
 * </ol>
 * 默认引擎 cn.bing.com（国内能直连）；也可以指定 duckduckgo / google / baidu。
 */
@Component
public class WebSearchTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build();

    private final HeadlessBrowser browser;

    public WebSearchTool(HeadlessBrowser browser) {
        this.browser = browser;
    }

    @Override
    public String getId() { return "tool.web.search"; }

    @Override
    public String getName() { return "web_search"; }

    @Override
    public String getDescription() {
        return "用无头浏览器（Edge/Chrome）在互联网上搜索，返回标题/链接/摘要；不需要任何 API 密钥";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.WEB_SEARCH; }

    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    // 【注意】这里以前写死 `isAvailableInMode = true`（搜索工具的"永远可用"老特例），
    // 结果极简模式（只该有文件类 + shell 类工具）里还能调 web_search，用户一眼就看出模式没生效。
    // 现在交给基类按"模式 + 类别"统一判断：极简模式下它根本不在工具清单里。

    @Override
    protected Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "搜索关键词"));
        props.put("maxResults", Map.of("type", "integer", "description", "最多返回几条", "default", 5));
        props.put("engine", Map.of("type", "string",
            "description", "搜索引擎：bing（默认，国内可直连）/ duckduckgo / google / baidu"));
        return Map.of("type", "object", "properties", props, "required", new String[] {"query"});
    }

    /** 一条搜索结果 */
    private record Hit(String title, String url, String snippet) {}

    /** 一次抓取的结果：页面文本 + 走的是哪条路（要如实告诉用户） */
    private record Fetch(String html, String how) {}

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query;
        try {
            query = getRequiredStringArg(arguments, "query");
        } catch (Exception e) {
            return error(e.getMessage());
        }
        int max = 5;
        try {
            if (arguments.get("maxResults") instanceof Number n) {
                max = Math.max(1, Math.min(20, n.intValue()));
            } else if (arguments.get("maxResults") instanceof String s && !s.isBlank()) {
                max = Math.max(1, Math.min(20, Integer.parseInt(s.trim())));
            }
        } catch (Exception ignored) {
            // 用默认值
        }
        String engine = getStringArg(arguments, "engine", "bing");
        if (engine == null || engine.isBlank()) {
            engine = "bing";
        }
        engine = engine.trim().toLowerCase();

        log.info("网络搜索（无头浏览器）: {} [{}]", query, engine);

        List<String> tried = new ArrayList<>();
        String[] order = engine.startsWith("bing")
            ? new String[] {"bing", "duckduckgo"}
            : new String[] {engine, "bing"};
        for (String eng : order) {
            Fetch fetch = fetch(searchUrl(eng, query), eng);
            List<Hit> hits = parse(eng, fetch.html(), max);
            tried.add(eng + "：" + fetch.how() + "（解析到 " + hits.size() + " 条）");
            if (!hits.isEmpty()) {
                return success(format(query, eng, fetch.how(), hits));
            }
        }
        return error("没搜到结果（" + query + "）。尝试过 → " + String.join("；", tried)
            + "\n建议：换个更短的关键词；或用 fetch_url 直接打开某个具体网址。");
    }

    /** 抓页面：优先无头浏览器，失败退到 HTTP 直取 */
    private Fetch fetch(String url, String engine) {
        String html = browser.dumpDom(url, 45);        // 冷启动要建 profile，给宽一点
        if (html != null && html.length() > 400) {
            return new Fetch(html, "无头浏览器 " + shortName(browser.exePath()));
        }
        String why = browser.available() ? "浏览器没拿到内容" : browser.unavailableReason();
        String viaHttp = httpGet(url, engine);
        if (viaHttp != null && viaHttp.length() > 400) {
            return new Fetch(viaHttp, "HTTP 直取（" + why + "）");
        }
        return new Fetch(null, "两条路都没成功（" + why + "）");
    }

    private String httpGet(String url, String engine) {
        try {
            Request.Builder rb = new Request.Builder().url(url).get()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            try (Response resp = httpClient.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    return null;
                }
                return resp.body().string();
            }
        } catch (Exception e) {
            log.warn("搜索页 HTTP 直取失败: {}", e.getMessage());
            return null;
        }
    }

    private String searchUrl(String engine, String q) {
        String enc = URLEncoder.encode(q, StandardCharsets.UTF_8);
        return switch (engine) {
            case "duckduckgo", "ddg" -> "https://html.duckduckgo.com/html/?q=" + enc;
            case "google" -> "https://www.google.com/search?num=20&q=" + enc;
            case "baidu" -> "https://www.baidu.com/s?wd=" + enc;
            default -> "https://cn.bing.com/search?q=" + enc;
        };
    }

    /** 按引擎解析结果；解析不出来就返回空表（调用方换引擎再试） */
    private List<Hit> parse(String engine, String html, int max) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        return switch (engine) {
            case "duckduckgo", "ddg" -> parseDuckDuckGo(html, max);
            case "google" -> parseGoogle(html, max);
            case "baidu" -> parseBaidu(html, max);
            default -> parseBing(html, max);
        };
    }

    private List<Hit> parseBing(String html, int max) {
        List<Hit> out = new ArrayList<>();
        Matcher li = Pattern.compile("(?s)<li[^>]*class=\"[^\"]*\\bb_algo\\b[^\"]*\"[^>]*>(.*?)</li>")
            .matcher(html);
        while (li.find() && out.size() < max) {
            String block = li.group(1);
            Matcher a = Pattern.compile("(?s)<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                .matcher(block);
            if (!a.find()) {
                continue;
            }
            String url = cleanBingUrl(a.group(1));
            String title = text(a.group(2));
            String snippet = "";
            Matcher p = Pattern.compile("(?s)<p[^>]*>(.*?)</p>").matcher(block);
            if (p.find()) {
                snippet = text(p.group(1));
            }
            if (!title.isBlank() && url.startsWith("http")) {
                out.add(new Hit(title, url, snippet));
            }
        }
        return out;
    }

    private List<Hit> parseDuckDuckGo(String html, int max) {
        List<Hit> out = new ArrayList<>();
        List<String> snippets = new ArrayList<>();
        Matcher s = Pattern.compile("(?s)<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>")
            .matcher(html);
        while (s.find()) {
            snippets.add(text(s.group(1)));
        }
        Matcher a = Pattern.compile("(?s)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\""
            + "[^>]*>(.*?)</a>").matcher(html);
        while (a.find() && out.size() < max) {
            String url = decodeDdgUrl(a.group(1));
            String title = text(a.group(2));
            if (!title.isBlank() && url.startsWith("http")) {
                int i = out.size();
                out.add(new Hit(title, url, i < snippets.size() ? snippets.get(i) : ""));
            }
        }
        return out;
    }

    private List<Hit> parseBaidu(String html, int max) {
        List<Hit> out = new ArrayList<>();
        Matcher a = Pattern.compile("(?s)<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
            .matcher(html);
        while (a.find() && out.size() < max) {
            String title = text(a.group(2));
            if (!title.isBlank()) {
                out.add(new Hit(title, a.group(1), ""));
            }
        }
        return out;
    }

    private List<Hit> parseGoogle(String html, int max) {
        List<Hit> out = new ArrayList<>();
        Matcher a = Pattern.compile("(?s)<a[^>]*href=\"(/url\\?q=[^\"]+|https?://[^\"]+)\"[^>]*>\\s*"
            + "<h3[^>]*>(.*?)</h3>").matcher(html);
        while (a.find() && out.size() < max) {
            String url = decodeGoogleUrl(a.group(1));
            String title = text(a.group(2));
            if (!title.isBlank() && url.startsWith("http")) {
                out.add(new Hit(title, url, ""));
            }
        }
        return out;
    }

    /** Bing 的链接常常是 ck/a?...&u=a1<base64url>，要解回真地址 */
    private String cleanBingUrl(String href) {
        if (href == null) {
            return "";
        }
        String h = href.replace("&amp;", "&");
        int i = h.indexOf("&u=a1");
        if (h.contains("bing.com/ck/a") && i >= 0) {
            String b64 = h.substring(i + 5);
            int amp = b64.indexOf('&');
            if (amp > 0) {
                b64 = b64.substring(0, amp);
            }
            try {
                String pad = b64 + "=".repeat((4 - b64.length() % 4) % 4);
                return new String(Base64.getUrlDecoder().decode(pad), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return h;
            }
        }
        return h;
    }

    /** DuckDuckGo 的链接是 //duckduckgo.com/l/?uddg=<urlencoded> */
    private String decodeDdgUrl(String href) {
        if (href == null) {
            return "";
        }
        String h = href.replace("&amp;", "&");
        int i = h.indexOf("uddg=");
        if (i >= 0) {
            String enc = h.substring(i + 5);
            int amp = enc.indexOf('&');
            if (amp > 0) {
                enc = enc.substring(0, amp);
            }
            return java.net.URLDecoder.decode(enc, StandardCharsets.UTF_8);
        }
        return h.startsWith("//") ? "https:" + h : h;
    }

    private String decodeGoogleUrl(String href) {
        String h = href == null ? "" : href.replace("&amp;", "&");
        if (h.startsWith("/url?q=")) {
            String q = h.substring(7);
            int amp = q.indexOf('&');
            if (amp > 0) {
                q = q.substring(0, amp);
            }
            return java.net.URLDecoder.decode(q, StandardCharsets.UTF_8);
        }
        return h;
    }

    /** 去标签 + 还原常见实体 */
    private String text(String htmlFragment) {
        if (htmlFragment == null) {
            return "";
        }
        String s = htmlFragment.replaceAll("(?s)<script.*?</script>", " ")
            .replaceAll("(?s)<style.*?</style>", " ")
            .replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
             .replace("&apos;", "'").replace("&hellip;", "\u2026").replace("&mdash;", "\u2014");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private String format(String query, String engine, String how, List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索「").append(query).append("」—— 引擎 ").append(engine)
          .append("，方式：").append(how).append("\n");
        sb.append("共 ").append(hits.size()).append(" 条结果：\n");
        int i = 1;
        for (Hit h : hits) {
            sb.append("\n").append(i++).append(". ").append(h.title()).append("\n");
            sb.append("   ").append(h.url()).append("\n");
            if (!h.snippet().isBlank()) {
                sb.append("   ").append(h.snippet().length() > 300
                    ? h.snippet().substring(0, 300) + "\u2026" : h.snippet()).append("\n");
            }
        }
        return sb.toString();
    }

    private String shortName(String path) {
        if (path == null || path.isBlank()) {
            return "未找到";
        }
        int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
