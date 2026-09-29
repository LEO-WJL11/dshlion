package com.lioncode.core.plugin.tool.shell;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 停止后台进程工具
 */
@Component
public class ShellStopTool extends AbstractToolPlugin {

    @Override
    public String getId() { return "tool.shell.stop"; }
    @Override
    public String getName() { return "stop_background"; }
    @Override
    public String getDescription() { return "停止后台运行的进程"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.SHELL; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "pid", Map.of("type", "string", "description", "进程ID")
        ), "required", new String[]{"pid"});
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String pid = getRequiredStringArg(arguments, "pid");
            Process process = ShellBackgroundTool.backgroundProcesses.remove(pid);
            if (process == null) {
                // 实测模型会拿一个自己编的 pid 来停（a7472d31）。
                // 把当前真在跑的后台进程 id 列出来，它下一轮就能用对。
                var running = ShellBackgroundTool.backgroundProcesses.keySet();
                return error("未找到进程: " + pid
                    + (running.isEmpty()
                        ? "（当前没有在跑的后台进程；先用 run_background 启动，它会返回 pid）"
                        : "（当前在跑的后台进程: " + String.join(", ", running) + "）"));
            }
            process.destroyForcibly();
            return success("进程已停止: " + pid);
        } catch (Exception e) {
            return error("停止进程失败: " + e.getMessage());
        }
    }
}
