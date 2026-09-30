package com.lioncode.core.plugin.tool.shell;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 后台Shell执行工具
 */
@Component
public class ShellBackgroundTool extends AbstractToolPlugin {

    static final Map<String, Process> backgroundProcesses = new ConcurrentHashMap<>();

    /** 每个后台进程最近的输出（排空管道用的同时也是排查线索） */
    static final Map<String, java.util.List<String>> processTails = new ConcurrentHashMap<>();

    /** 把子进程的输出读掉（丢弃 + 留最后 50 行），不读的话管道满了会把子进程堵死。 */
    private static void drain(java.io.InputStream in, java.util.List<String> tail) {
        Thread t = new Thread(() -> {
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > 50) {
                        tail.remove(0);
                    }
                }
            } catch (Exception ignore) {
                // 进程结束了
            }
        }, "lionbox-bg-drain");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public String getId() { return "tool.shell.background"; }
    @Override
    public String getName() { return "run_background"; }
    @Override
    public String getDescription() { return "在后台执行长时间运行的命令"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.SHELL; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "command", Map.of("type", "string", "description", "命令"),
            "workdir", Map.of("type", "string", "description", "工作目录")
        ), "required", new String[]{"command"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String command = getRequiredStringArg(arguments, "command");
            String workdir = getStringArg(arguments, "workdir", null);

            // 【实测两个坑】
            // 1) 原来走 `cmd /c`，与 execute_command 的 PowerShell 不一致：模型写 ps 语法
            //    或 Unix 别名时直接失败（用户那一跑就是 ❌ 一次、换写法再来一次 ✅）。
            // 2) workdir 直接 new File(相对路径) 会按**软件自己的**工作目录解析，
            //    不是工作区 —— 相对目录必然"目录不存在"，所以先 resolvePath + 检查。
            String dir = null;
            if (workdir != null && !workdir.isBlank()) {
                dir = resolvePath(workdir);
                if (!new File(dir).isDirectory()) {
                    return error("工作目录不存在: " + dir + "（先 create_directory 建出来，或去掉 workdir 用工作区根目录）");
                }
            } else if (currentWorkspace() != null) {
                dir = currentWorkspace();
            }

            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command);
            } else {
                pb.command("sh", "-c", command);
            }
            if (dir != null) {
                pb.directory(new File(dir));
            }

            Process process = pb.start();

            // 【必须排空输出】原来没人读子进程的 stdout/stderr：后台命令输出一多就把
            // 管道缓冲区写满，子进程**卡死**在写上面（表现是"后台任务永远不结束"）。
            // 这里开两个守护线程读掉，并留最后 50 行方便排查。
            java.util.List<String> tail = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            drain(process.getInputStream(), tail);
            drain(process.getErrorStream(), tail);

            String pid = UUID.randomUUID().toString().substring(0, 8);
            backgroundProcesses.put(pid, process);
            processTails.put(pid, tail);

            return success("后台进程已启动，PID: " + pid
                + "\n工作目录: " + (dir == null ? "(继承)" : dir)
                + "\n使用 stop_background 工具停止");
        } catch (Exception e) {
            return error("启动后台进程失败: " + e.getMessage());
        }
    }
}
