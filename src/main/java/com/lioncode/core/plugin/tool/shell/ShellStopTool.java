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
            "pid", Map.of("type", "string", "description", "进程ID（省略则停掉最近启动的那个后台进程）")
        ), "required", new String[0]);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String pid = getStringArg(arguments, "pid", null);
            var background = ShellBackgroundTool.backgroundProcesses;

            // 【实测】模型常想"把后台的东西停掉"，但手上没有 pid（上一轮 run_background
            // 的返回值它没记住），于是编一个 id 传进来 → 必然 ❌。所以允许不给 pid：
            // 只有一个在跑就直接停它（这才是它真正想要的），多个就把名单回给它挑。
            if (pid == null || pid.isBlank() || "null".equalsIgnoreCase(pid.trim())) {
                var ids = new java.util.ArrayList<>(background.keySet());
                if (ids.isEmpty()) {
                    return error("当前没有在跑的后台进程（run_background 启动后会返回 pid）");
                }
                if (ids.size() > 1) {
                    return error("有多个后台进程在跑，指定要停哪个：pid=" + String.join(" / ", ids));
                }
                pid = ids.get(0);
            }

            Process process = background.remove(pid);
            if (process == null) {
                // 实测模型会拿一个自己编的 pid 来停（a7472d31）。
                // 把当前真在跑的后台进程 id 列出来，它下一轮就能用对。
                var running = background.keySet();
                return error("未找到进程: " + pid
                    + (running.isEmpty()
                        ? "（当前没有在跑的后台进程；先用 run_background 启动，它会返回 pid）"
                        : "（当前在跑的后台进程: " + String.join(", ", running) + "）"));
            }
            process.destroyForcibly();
            ShellBackgroundTool.processTails.remove(pid);   // 顺带清掉它的输出尾巴
            return success("进程已停止: " + pid);
        } catch (Exception e) {
            return error("停止进程失败: " + e.getMessage());
        }
    }
}
