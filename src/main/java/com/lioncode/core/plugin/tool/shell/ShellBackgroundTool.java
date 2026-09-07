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
            
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            if (workdir != null) pb.directory(new File(workdir));
            
            Process process = pb.start();
            String pid = UUID.randomUUID().toString().substring(0, 8);
            backgroundProcesses.put(pid, process);
            
            return success("后台进程已启动，PID: " + pid + "\n使用 stop_background 工具停止");
        } catch (Exception e) {
            return error("启动后台进程失败: " + e.getMessage());
        }
    }
}
