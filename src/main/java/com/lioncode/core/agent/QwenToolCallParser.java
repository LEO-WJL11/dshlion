package com.lioncode.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qwen / llama.cpp 模板原生工具调用的解析器。
 *
 * <p>模型（我们的 GGUF 就是 Qwen 模板）最自然的输出是这个样子：
 *
 * <pre>
 * 我先了解工作区环境，然后逐个调用工具做测试。
 * &lt;tool_call&gt;
 * &lt;function=execute_command&gt;
 * &lt;parameter=command&gt;
 * ls -la &amp;&amp; pwd
 * &lt;/parameter&gt;
 * &lt;/function&gt;
 * &lt;/tool_call&gt;
 * </pre>
 *
 * <p>这**正是 llama-server 开 {@code --jinja} 时服务端会帮我们解析成标准 tool_calls 的格式**。
 * 但我们没开 --jinja（本地运行时故意不下发 tools），所以这段文本原样落到 harness 手上；
 * 而原来的解析器只认 {@code <name>/<arguments>} 和 JSON 两种写法，遇到 {@code <function=...>}
 * 会拿去当 JSON 解析，报 "Unexpected character ('<')"，整轮工具调用作废 ——
 * 模型白生成一轮，用户看到的就是"它说要调用工具，然后什么也没干"。
 *
 * <p>抽成独立的静态类（不依赖 Spring、不依赖 Jackson）是为了能直接跑自测：
 * {@code java -cp target/classes com.lioncode.core.agent.QwenToolCallParser}
 */
public final class QwenToolCallParser {

    /** 读 JSON 参数用（只读，线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();


    private QwenToolCallParser() {
    }

    /** 一次解析出来的调用：工具名 + 参数字典 */
    public static final class Call {
        private final String name;
        private final Map<String, Object> arguments;

        public Call(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String name() {
            return name;
        }

        public Map<String, Object> arguments() {
            return arguments;
        }
    }

    /** <function=工具名> … </function>；工具名允许字母下划线开头，后面可带点、横线 */
    private static final Pattern FUNCTION =
        Pattern.compile("(?s)<function\\s*=\\s*([A-Za-z_][\\w.\\-]*)\\s*>(.*?)</function>");

    /** <parameter=参数名>值</parameter> */
    private static final Pattern PARAMETER =
        Pattern.compile("(?s)<parameter\\s*=\\s*([A-Za-z_][\\w.\\-]*)\\s*>(.*?)</parameter>");

    /**
     * 解析文本里所有的模板原生工具调用（有没有 &lt;tool_call&gt; 外壳都认）。
     *
     * @param text 模型输出
     * @return 解析结果；没有就返回空列表
     */
    public static List<Call> parse(String text) {
        List<Call> calls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return calls;
        }
        Matcher fn = FUNCTION.matcher(text);
        while (fn.find()) {
            String name = fn.group(1).trim();
            if (name.isEmpty()) {
                continue;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            Matcher pm = PARAMETER.matcher(fn.group(2));
            while (pm.find()) {
                args.put(pm.group(1).trim(), unescape(pm.group(2)));
            }
            if (args.isEmpty()) {
                // 【兜底】模型也常把参数写成 JSON 对象塞在 function 里（我们自己的用例、
                // 以及部分模板都这么发）。只认 <parameter=…> 的话这种调用会被当成
                // "没有参数"，工具回一句"缺少必需参数"，整轮就废了 —— 实测踩过。
                args.putAll(parseJsonArguments(fn.group(2)));
            }
            calls.add(new Call(name, args));
        }
        return calls;
    }

    /**
     * 把模板原生的工具调用块从正文里删掉，剩下的才是给用户看的文字。
     * 先删 {@code <tool_call>…</tool_call>} 整块（含里面的 function），
     * 再兜底删掉没被包裹的 {@code <function=…>…</function>}。
     */
    public static String stripCalls(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = Pattern.compile("(?s)<tool_call>.*?</tool_call>").matcher(text).replaceAll(" ");
        out = FUNCTION.matcher(out).replaceAll(" ");
        return out.trim();
    }

    /**
     * 把 {@code <function=…>} 里那块内容当 JSON 对象读出来（读不出来就返回空 Map）。
     *
     * <p>兼容三种常见写法：纯 JSON、```json 围栏、以及前面带一句解释的文字 + JSON。
     */
    private static Map<String, Object> parseJsonArguments(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        String t = body.trim();
        // 去掉 ```json … ``` 围栏
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        // 只取第一个 { 到最后一个 }（前面可能有一句"我来调用一下："）
        int b = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (b < 0 || e <= b) {
            return Map.of();
        }
        String json = t.substring(b, e + 1);
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception ex) {
            return Map.of();      // 不是 JSON 就当没参数，跟以前一样
        }
    }

    /** 值就是原样的文本；只去掉首尾空白（多行命令要保留内部换行） */
    private static String unescape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    // ------------------------------------------------------------------
    // 自测：java -cp target/classes com.lioncode.core.agent.QwenToolCallParser
    // ------------------------------------------------------------------
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 1) 真实失败现场（2026-09-28 23:03 用户会话里的原文）
        String real = "我先了解工作区环境，然后逐个调用工具做测试。<tool_call>\n<function=execute_command>\n"
                    + "<parameter=command>\nls -la && pwd\n</parameter>\n</function>\n</tool_call>";
        List<Call> c1 = parse(real);
        check("真实失败现场：解析出 1 个调用", c1.size() == 1);
        if (c1.size() == 1) {
            check("工具名 = execute_command", "execute_command".equals(c1.get(0).name()));
            check("command 参数 = ls -la && pwd",
                "ls -la && pwd".equals(c1.get(0).arguments().get("command")));
        }

        // 2) 多个参数
        String multi = "<tool_call><function=write_file><parameter=path>a.txt</parameter>"
                     + "<parameter=content>hello\nworld</parameter></function></tool_call>";
        List<Call> c2 = parse(multi);
        check("多参数：1 个调用 2 个参数", c2.size() == 1 && c2.get(0).arguments().size() == 2);
        if (c2.size() == 1) {
            check("多行内容保留换行", "hello\nworld".equals(c2.get(0).arguments().get("content")));
        }

        // 3) 没有 <tool_call> 外壳
        List<Call> c3 = parse("<function=list_directory><parameter=path>.</parameter></function>");
        check("缺外壳也认", c3.size() == 1 && "list_directory".equals(c3.get(0).name()));

        // 4) 一次多个调用（harness 会裁剪成一个，但解析层要都拿到）
        String two = "<tool_call><function=git_status><parameter=path>.</parameter></function></tool_call>"
                   + "<tool_call><function=git_log><parameter=count>3</parameter></function></tool_call>";
        check("一次两个调用都能解析", parse(two).size() == 2);

        // 5) 没有参数
        List<Call> c5 = parse("<tool_call><function=system_info></function></tool_call>");
        check("无参数调用参数为空 Map", c5.size() == 1 && c5.get(0).arguments().isEmpty());

        // 6) 普通文本不该被误判
        check("普通正文解析结果为空", parse("我来看看这个目录里有什么。").isEmpty());

        // 7) 残缺（没有 </function>）不该崩，也不该硬凑
        check("残缺块不崩", parse("<tool_call><function=execute_command>").isEmpty());

        // 8) 剥离后只剩正文
        check("剥离工具调用后只剩正文",
            stripCalls(real).equals("我先了解工作区环境，然后逐个调用工具做测试。"));

        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label);
    }
}
