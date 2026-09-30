package com.lioncode.core.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具**参数名**归一化：模型写歪的键名 → 工具声明的真实键名。
 *
 * <p>【为什么要有这个文件】文本通道下模型是自己手打 XML 标签的，参数名写歪是常态：
 * {@code <parameter=file_path>} 而工具声明的是 {@code path}、
 * {@code <parameter=max_depth>} 而声明的是 {@code maxDepth}。
 * 键名差一个字母，工具里 {@code args.get("path")} 就是 null，
 * 用户看到的是"❌ read_file: 缺少 path 参数"——模型明明已经把值写出来了。
 * 本机 11 token/s，重来一轮就是十几秒，这种失败纯属浪费。</p>
 *
 * <p>做法很保守：**只改键名，不动值**；只在"唯一命中"时才改名（多个候选就放弃，
 * 让工具照旧报缺参数），所以不会把正确的调用改坏。</p>
 */
public final class ToolArgAliases {

    private ToolArgAliases() {
    }

    /** 工具声明的键名 → 模型常用的其他写法。 */
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("path", List.of("file_path", "filepath", "filename", "file_name", "file",
            "target_path", "dir", "directory", "location", "pathname", "abspath", "abs_path"));
        ALIASES.put("source", List.of("src", "from", "old", "old_path", "oldpath", "source_path",
            "src_path", "from_path", "origin"));
        ALIASES.put("target", List.of("dest", "destination", "to", "new", "new_path", "newpath",
            "target_path", "dest_path", "destination_path", "dst", "to_path"));
        ALIASES.put("content", List.of("text", "data", "body", "value", "contents", "new_text",
            "new_content", "newText", "newContent", "payload"));
        ALIASES.put("command", List.of("cmd", "command_line", "commandline", "shell_command",
            "script", "run", "cmdline"));
        ALIASES.put("pattern", List.of("regex", "query", "search", "keyword", "search_pattern",
            "regexp", "expr"));
        ALIASES.put("query", List.of("q", "keyword", "search", "term", "text", "search_query"));
        ALIASES.put("input", List.of("text", "data", "value", "payload", "str", "string"));
        ALIASES.put("url", List.of("uri", "link", "address", "href", "endpoint", "target_url"));
        ALIASES.put("message", List.of("msg", "commit_message", "text", "description"));
        ALIASES.put("lines", List.of("line_count", "num_lines", "n", "number_of_lines", "count"));
        ALIASES.put("count", List.of("n", "num", "times", "number", "limit", "amount", "total"));
        ALIASES.put("maxResults", List.of("limit", "max_results", "maxresults", "top", "max",
            "maximum", "size", "num_results"));
        ALIASES.put("maxDepth", List.of("depth", "max_depth", "maxdepth", "levels", "level", "d"));
        ALIASES.put("name", List.of("key", "var", "variable", "env_name", "variable_name"));
        ALIASES.put("text", List.of("value", "str", "string", "content_text"));
        ALIASES.put("oldText", List.of("old_text", "oldtext", "old", "search", "from_text",
            "find", "original", "original_text"));
        ALIASES.put("startLine", List.of("start_line", "startline", "from_line", "begin_line",
            "start", "line_start"));
        ALIASES.put("endLine", List.of("end_line", "endline", "to_line", "finish_line", "end",
            "line_end"));
        ALIASES.put("operation", List.of("action", "op", "mode_action"));
        ALIASES.put("format", List.of("fmt", "format_string", "pattern_format"));
        ALIASES.put("domain", List.of("hostname", "host", "name_domain"));
        ALIASES.put("algorithm", List.of("algo", "hash_type", "method"));
        ALIASES.put("encoding", List.of("charset", "file_encoding", "enc"));
        ALIASES.put("recursive", List.of("recurse", "recursion", "r"));
        ALIASES.put("timeout", List.of("timeout_seconds", "timeoutSeconds", "seconds", "time_limit"));
        ALIASES.put("workdir", List.of("cwd", "working_directory", "working_dir", "wd"));
        ALIASES.put("savePath", List.of("save_path", "output", "output_path", "dest_path"));
        ALIASES.put("filePattern", List.of("file_pattern", "glob", "include", "files", "filter"));
        ALIASES.put("useRegex", List.of("use_regex", "regex_mode", "is_regex"));
        ALIASES.put("action", List.of("op", "operation_type", "command_action"));
        ALIASES.put("expression", List.of("expr", "cron", "cron_expression"));
        ALIASES.put("branch", List.of("branch_name", "ref_name"));
        ALIASES.put("value", List.of("val", "input_value", "number"));
        ALIASES.put("from", List.of("source_lang", "from_lang", "source"));
        ALIASES.put("to", List.of("target_lang", "to_lang", "target"));
    }

    /** 去掉大小写和分隔符差异（file_path ↔ filePath ↔ filepath）。 */
    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c != '_' && c != '-' && c != ' ' && c != '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 在模型给的键里，找出对应"声明键"的那个键名。
     *
     * @param declared 工具声明的参数名
     * @param given    模型实际给的键集合
     * @return 匹配到的键名；没把握（0 个或多个候选）时返回 null
     */
    public static String matchKey(String declared, Collection<String> given) {
        if (declared == null || given == null || given.isEmpty()) {
            return null;
        }
        // 1) 忽略大小写完全相同
        for (String g : given) {
            if (g != null && g.equalsIgnoreCase(declared)) {
                return g;
            }
        }
        // 2) 去掉分隔符后相同（max_depth ↔ maxDepth）
        String sq = squash(declared);
        String only = null;
        for (String g : given) {
            if (g != null && squash(g).equals(sq)) {
                if (only != null) {
                    return null;    // 多个候选 → 不猜
                }
                only = g;
            }
        }
        if (only != null) {
            return only;
        }
        // 3) 别名表
        List<String> alts = ALIASES.get(declared);
        if (alts == null) {
            return null;
        }
        String hit = null;
        for (String alt : alts) {
            for (String g : given) {
                if (g != null && g.equalsIgnoreCase(alt)) {
                    if (hit != null) {
                        return null;    // 多个候选 → 不猜
                    }
                    hit = g;
                }
            }
        }
        return hit;
    }

    /** 自检：java -cp ... com.lioncode.core.agent.ToolArgAliases */
    public static void main(String[] args) {
        int pass = 0;
        int fail = 0;
        String[][] cases = {
            {"path", "file_path", "file_path"}, {"path", "FilePath", "FilePath"},
            {"maxDepth", "max_depth", "max_depth"}, {"maxDepth", "maxdepth", "maxdepth"},
            {"content", "contents", "contents"}, {"command", "cmd", "cmd"},
            {"source", "src", "src"}, {"target", "destination", "destination"},
            {"path", "unknown_key", null}, {"path", "", null},
        };
        for (String[] c : cases) {
            List<String> given = c[1].isEmpty() ? List.of() : List.of(c[1]);
            String got = matchKey(c[0], given);
            boolean ok = c[2] == null ? got == null : c[2].equals(got);
            System.out.printf("%-12s + %-16s -> %-16s %s%n", c[0], c[1], got,
                ok ? "OK" : "FAIL(期望 " + c[2] + ")");
            if (ok) {
                pass++;
            } else {
                fail++;
            }
        }
        System.out.printf("参数别名自检 %d 通过 / %d 失败%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
