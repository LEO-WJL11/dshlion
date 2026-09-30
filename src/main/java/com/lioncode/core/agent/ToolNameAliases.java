package com.lioncode.core.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具名归一化：把模型嘴里的名字映射到我们真实注册的工具名。
 *
 * <p>【为什么要有这个文件】本地跑的是 4bit 量化的 Qwen（IQ4_XS），它对"工具名"的回忆
 * 明显偏向自己训练语料里的通用叫法：想列目录就写 {@code ls} / {@code list_files}，
 * 想读文件就写 {@code cat} / {@code read}，想跑命令就写 {@code bash}。这些名字在我们这里
 * 一个都不存在，于是用户看到的是"未找到工具: ls"，白烧一轮推理（本机 11 token/s，一轮十几秒）。</p>
 *
 * <p>归一化**只在精确查找失败之后**才做，所以永远不会把已经正确的调用改坏；
 * 而且只在"有唯一把握"时才认（精确别名表 / 忽略大小写和分隔符后唯一命中 / 编辑距离 ≤1 的唯一候选），
 * 拿不准就返回 null，让上层照旧报"未找到工具 + 建议"，不会把用户带沟里。</p>
 */
public final class ToolNameAliases {

    private ToolNameAliases() {
    }

    /**
     * 别名表：模型常用的叫法 → 本项目的真实工具名。
     * <p>只收"语义无歧义"的（head 绝不会被理解成别的），有歧义的一律不收。</p>
     */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    private static void alias(String canonical, String... names) {
        for (String n : names) {
            ALIASES.put(n, canonical);
        }
    }

    static {
        alias("list_directory", "ls", "dir", "list", "listdir", "list_dir", "list_files",
            "listfiles", "list_folder", "list_directory_contents", "show_directory",
            // 压测里模型真的造过 ls_directory / list_dir_contents 这种"半对"的名字：
            // 归一化表里没有就直接被判"未找到工具"，白烧一轮（本机一轮十几秒）
            "ls_directory", "ls_dir", "lsdir", "lsdirs", "list_dir_contents", "list_dir_content",
            "ls_l", "ls_dir_list", "list_directory_content", "dir_list", "dirlist",
            "show_files", "list_all_files", "list_dir_files");
        alias("read_file", "cat", "read", "readfile", "read_text", "readtext", "view",
            "view_file", "open_file", "get_file_content", "file_read", "show_file");
        alias("head_tail_file", "head", "tail", "head_file", "tail_file", "read_head",
            "read_tail", "first_lines", "last_lines");
        alias("search_in_files", "grep", "search", "search_text", "search_content", "rg",
            "ripgrep", "find_in_files", "search_files", "grep_files", "search_in_file");
        alias("glob_files", "find", "glob", "ls_files", "glob_pattern", "match_files",
            "find_files", "list_files_by_pattern");
        alias("line_count", "wc", "wc_l", "linecount", "count_lines", "lines_count",
            "file_line_count", "count_loc", "loc");
        alias("word_count", "wc_words", "wordcount", "count_words", "file_word_count");
        alias("write_file", "write", "writefile", "save_file", "write_text", "create_or_update_file",
            "put_file", "overwrite_file");
        alias("create_file", "touch", "new_file", "create_empty_file", "make_file");
        alias("modify_file", "edit", "edit_file", "replace_in_file", "replace", "str_replace",
            "strreplace", "patch_file", "update_file", "insert_text");
        alias("append_file", "append", "append_to_file", "add_to_file", "append_text");
        alias("delete_file", "rm", "del", "remove", "delete", "delete_path", "rm_file",
            "remove_file", "unlink");
        alias("move_file", "mv", "move", "rename", "rename_file", "move_path");
        alias("copy_file", "cp", "copy", "copy_path", "duplicate_file");
        alias("create_directory", "mkdir", "create_dir", "new_directory", "make_directory");
        alias("file_info", "stat", "file_stat", "fileinfo", "get_file_info", "ls_l");
        alias("directory_tree", "tree", "dir_tree", "show_tree", "folder_tree");
        alias("execute_command", "bash", "sh", "shell", "run", "exec", "cmd", "command",
            "run_command", "execute", "terminal", "shell_execute", "powershell", "run_shell",
            "execute_shell", "run_terminal", "cli");
        alias("run_background", "bg", "background", "run_in_background", "start_background",
            "background_run", "spawn_process");
        alias("stop_background", "kill", "kill_process", "stop_process", "bg_stop");
        alias("fetch_url", "curl", "wget", "fetch", "http_fetch", "get_url", "fetch_page",
            "download_page");
        alias("http_get", "httpget", "get_request", "http_request");
        alias("http_post", "httppost", "post_request");
        alias("web_search", "websearch", "search_web", "browser_search", "google", "search_internet");
        alias("download_file", "wget_file", "download");
        alias("hash", "sha256", "md5", "sha1", "hash_string", "hash_file", "checksum");
        alias("base64", "b64", "base64_encode", "base64_decode", "encode_base64");
        alias("json_format", "json", "format_json", "json_parse", "json_beautify", "pretty_json");
        alias("yaml_process", "yaml", "format_yaml", "yaml_parse");
        alias("generate_uuid", "uuid", "uuid_generate", "gen_uuid", "new_uuid");
        alias("get_env", "env", "getenv", "environment", "env_var", "printenv");
        alias("dns_lookup", "dns", "nslookup", "resolve_dns", "dns_resolve");
        alias("timestamp", "time", "now", "current_time", "date", "get_time", "clock");
        alias("system_info", "sysinfo", "systeminfo", "system", "sys_info", "machine_info");
        alias("working_directory", "pwd", "cwd", "get_cwd", "get_working_directory");
        alias("translate", "translation", "translate_text");
        alias("regex_test", "regex", "regex_match", "test_regex");
        alias("string_utils", "string", "strings", "string_ops");
        alias("format_code", "format", "prettier", "formatter");
        alias("git_status", "gitstatus", "status");
        alias("git_log", "gitlog", "log");
        alias("git_diff", "gitdiff", "diff_git");
        alias("git_commit", "gitcommit", "commit");
        alias("git_branch", "gitbranch", "branch", "checkout");
        alias("git_init", "gitinit", "init_git");
        alias("git_remote", "gitremote", "remote");
        alias("git_stash", "gitstash", "stash");
        alias("git_reset", "gitreset", "reset");
        alias("diff_text", "diff", "compare_text", "text_diff");
        alias("markdown_render", "markdown", "md_render", "render_markdown");
        alias("change_permissions", "chmod", "permissions", "set_permissions");
        alias("escape_string", "escape", "unescape", "escape_text");
        alias("number_convert", "convert_number", "base_convert", "radix_convert");
        alias("ask_user", "ask", "question", "ask_question", "prompt_user");
        alias("cron_parse", "cron", "parse_cron");
    }

    /** 去掉大小写/分隔符差异后的形态，用于"忽略大小写和 - _ 空格"的宽松匹配。 */
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
     * 把模型给的工具名解析成真实工具名。
     *
     * @param rawName 模型写的名字
     * @param known   当前真实注册的工具名（大小写按原样）
     * @return 真实工具名；没把握时返回 null（上层照旧报错并给建议）
     */
    public static String resolve(String rawName, List<String> known) {
        if (rawName == null || rawName.isBlank() || known == null || known.isEmpty()) {
            return null;
        }
        String name = rawName.trim();
        // 1) 精确（大小写不敏感）命中：模型只是大小写写错，直接认
        for (String k : known) {
            if (k.equalsIgnoreCase(name)) {
                return k;
            }
        }
        // 2) 别名表：exact → squashed
        String hit = ALIASES.get(name.toLowerCase(Locale.ROOT));
        if (hit == null) {
            hit = ALIASES.get(squash(name));
        }
        if (hit != null) {
            for (String k : known) {
                if (k.equals(hit)) {
                    return k;
                }
            }
            // 别名表里有、但当前模式没开放这个工具 → 不硬转，让上层正常报错
            return null;
        }
        // 3) 忽略分隔符后唯一命中（file_path ↔ filepath、git-commit ↔ git_commit）
        String sq = squash(name);
        String only = null;
        for (String k : known) {
            if (squash(k).equals(sq)) {
                if (only != null) {
                    return null;   // 不止一个 → 有歧义，不猜
                }
                only = k;
            }
        }
        if (only != null) {
            return only;
        }
        // 4) 名字里"包着"一个真实工具名：read_file_content / do_list_directory /
        //    run_execute_command / git_status_now 这类"加料"写法。要求唯一命中，
        //    多个候选就放弃（宁可报"未找到工具"，也不要猜错工具去执行）。
        String contained = null;
        for (String k : known) {
            if (k.length() >= 5 && name.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))) {
                if (contained != null) {
                    return null;
                }
                contained = k;
            }
        }
        if (contained != null) {
            return contained;
        }
        // 5) 编辑距离 ≤1 且唯一：处理单个字母打错（git_statuss → git_status）
        String near = null;
        for (String k : known) {
            if (editDistanceAtMostOne(name.toLowerCase(Locale.ROOT), k.toLowerCase(Locale.ROOT))) {
                if (near != null) {
                    return null;   // 多个候选 → 不猜，交给"你是不是想用"提示
                }
                near = k;
            }
        }
        return near;
    }

    /** 编辑距离是否 ≤1（只算增/删/改一个字符，不做完整 DP，够用且快）。 */
    static boolean editDistanceAtMostOne(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        if (la == lb) {
            int diff = 0;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) != b.charAt(i) && ++diff > 1) {
                    return false;
                }
            }
            return true;
        }
        String longer = la > lb ? a : b;
        String shorter = la > lb ? b : a;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < longer.length() && j < shorter.length()) {
            if (longer.charAt(i) == shorter.charAt(j)) {
                i++;
                j++;
            } else {
                if (skipped) {
                    return false;
                }
                skipped = true;
                i++;
            }
        }
        return true;
    }

    /** 别名表大小（自检用）。 */
    public static int size() {
        return ALIASES.size();
    }

    /** 自检：java -cp ... com.lioncode.core.agent.ToolNameAliases */
    public static void main(String[] args) {
        List<String> known = List.of("read_file", "list_directory", "execute_command",
            "head_tail_file", "line_count", "git_status", "git_commit");
        int pass = 0;
        int fail = 0;
        String[][] cases = {
            {"ls", "list_directory"}, {"list_files", "list_directory"}, {"List_Directory", "list_directory"},
            {"ls_directory", "list_directory"}, {"read_file_content", "read_file"},
            {"do_list_directory", "list_directory"}, {"Status", "git_status"},
            {"cat", "read_file"}, {"read", "read_file"}, {"head", "head_tail_file"},
            {"bash", "execute_command"}, {"run_command", "execute_command"}, {"wc", "line_count"},
            {"git_statuss", "git_status"}, {"git-commit", "git_commit"}, {"readfile", "read_file"},
            {"delete_directory_placeholder", null}, {"translate", null}, {"", null},
        };
        for (String[] c : cases) {
            String got = resolve(c[0], known);
            boolean ok = c[1] == null ? got == null : c[1].equals(got);
            System.out.printf("%-30s -> %-20s %s%n", c[0], got, ok ? "OK" : "FAIL(期望 " + c[1] + ")");
            if (ok) {
                pass++;
            } else {
                fail++;
            }
        }
        System.out.printf("别名表 %d 条；自检 %d 通过 / %d 失败%n", size(), pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
