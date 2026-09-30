package com.lioncode.core.plugin.tool.git;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git Remote管理工具
 */
@Component
public class GitRemoteTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.git.remote"; }
    @Override
    public String getName() { return "git_remote"; }
    @Override
    public String getDescription() { return "查看和管理Git远程仓库"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.GIT; }
    @Override
    public PermissionLevel getRequiredPermission() { return PermissionLevel.READ_ONLY; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "path", Map.of("type", "string", "description", "仓库路径"),
            "action", Map.of("type", "string",
                "description", "list / show / add / remove / get-url / set-url"),
            "name", Map.of("type", "string", "description", "远程名（add/remove/show 用，如 origin）"),
            "url", Map.of("type", "string", "description", "仓库地址（add 用）")
        ), "required", new String[]{"path", "action"});
    }

    /** 猜一个远程名：没配过远程就叫 origin，否则用地址里的仓库名（去掉 .git） */
    private String inferRemoteName(String repoPath, String url) {
        try {
            Process p = new ProcessBuilder(gitExecutable(), "remote")
                .directory(new File(repoPath)).redirectErrorStream(true).start();
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if (out.isEmpty()) {
                    return "origin";               // 一个远程都没有 → 惯例就是 origin
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            // 问不出来就按仓库名猜
        }
        String base = url.replaceAll("[#?].*$", "").replaceAll("/+$", "");
        int i = Math.max(base.lastIndexOf('/'), base.lastIndexOf(':'));
        if (i >= 0 && i + 1 < base.length()) {
            base = base.substring(i + 1);
        }
        base = base.replaceAll("\\.git$", "").trim();
        return base.isEmpty() ? "origin" : base;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String path = resolvePath(getRequiredStringArg(arguments, "path"));
            String action = getRequiredStringArg(arguments, "action");

            ProcessBuilder pb;
            switch (action) {
                case "list", "ls" -> pb = new ProcessBuilder(gitExecutable(), "remote", "-v");
                case "show" -> {
                    String name = getStringArg(arguments, "name", "origin");
                    // -n：只读本地配置，不联网。实测不加 -n 时 git 会去连远端、等凭据卡死。
                    pb = new ProcessBuilder(gitExecutable(), "remote", "show", "-n",
                        name == null || name.isBlank() ? "origin" : name);
                }
                // 实测模型想 add（"仅支持list/show操作"直接卡住它）。加远程是很常用的操作，
                // 补上 add/remove；报错里也把支持的操作列全。
                case "add" -> {
                    String name = getStringArg(arguments, "name", null);
                    String url = getStringArg(arguments, "url", null);
                    if (url == null || url.isBlank()) {
                        return error("add 操作至少要给 url（仓库地址），例如 "
                            + "{\"action\":\"add\",\"url\":\"https://github.com/you/repo.git\"}"
                            + "；name 可以不给，会自动取 origin 或仓库名");
                    }
                    // name 经常被漏传（实测就是这么失败的）：没配过远程就叫 origin，
                    // 已经有一个远程了就用地址里的仓库名，别为了个名字把整件事卡住。
                    if (name == null || name.isBlank()) {
                        name = inferRemoteName(path, url);
                    }
                    pb = new ProcessBuilder(gitExecutable(), "remote", "add", name, url);
                }
                case "get-url", "geturl", "url" -> {
                    // 实测模型要读远程地址（get-url），我们只支持 list/show/add/remove 就卡住了。
                    String name = getStringArg(arguments, "name", "origin");
                    pb = new ProcessBuilder(gitExecutable(), "remote", "get-url",
                        name == null || name.isBlank() ? "origin" : name);
                }
                case "set-url", "seturl" -> {
                    String name = getStringArg(arguments, "name", "origin");
                    String url = getStringArg(arguments, "url", null);
                    if (url == null || url.isBlank()) {
                        return error("set-url 操作需要 url 参数");
                    }
                    pb = new ProcessBuilder(gitExecutable(), "remote", "set-url",
                        name == null || name.isBlank() ? "origin" : name, url);
                }
                case "remove", "rm", "delete" -> {
                    String name = getStringArg(arguments, "name", null);
                    if (name == null || name.isBlank()) {
                        return error("remove 操作需要 name（要删掉的远程名）");
                    }
                    pb = new ProcessBuilder(gitExecutable(), "remote", "remove", name);
                }
                default -> {
                    return error("未知操作: " + action
                        + "。支持 list / show / add / remove / get-url / set-url"
                        + "（add/set-url 需要 url，其余需要 name）");
                }
            }

            pb.directory(new File(path));
            pb.redirectErrorStream(true);
            gitEnv(pb);
            Process process = pb.start();
            // 【顺序要紧】先等进程结束再读：readAllBytes() 会一直阻塞到 stdout 关闭，
            // 而 git 在等凭据/网络时不会关（实测卡了 3 分钟），先读就等于没有超时。
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return error("git 命令超时（30 秒没返回）：多半在等网络或凭据。"
                    + "远程操作用 -n 只看本地配置，或先确认网络/凭据。");
            }
            String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!output.isEmpty()) {
                return success(output);
            }
            // 没输出 != 失败：git remote add / set-url / remove 成功时本来就不打印任何东西。
            // 以前这里一律回"（无远程仓库）"，add 成功看着也像没加上。
            String nm = getStringArg(arguments, "name", "");
            String u = getStringArg(arguments, "url", "");
            return success(switch (action) {
                case "add" -> "已添加远程 " + nm + " → " + u + "（git 成功时本来就没有输出）";
                case "set-url", "seturl" -> "已把远程 " + nm + " 的地址改成 " + u;
                case "remove", "rm" -> "已删除远程 " + nm;
                case "list", "ls" -> "（这个仓库没有配置任何远程）";
                default -> "（git 没有输出，命令已执行）";
            });
        } catch (Exception e) {
            return error("Git remote操作失败: " + e.getMessage());
        }
    }
}
