package com.lioncode.core.plugin.tool.shell;

import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell命令执行工具
 * 
 * 在工作区目录中执行Shell命令，捕获stdout和stderr。
 */
@Component
public class ShellExecuteTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(ShellExecuteTool.class);

    @Override
    public String getId() { return "tool.shell.execute"; }

    @Override
    public String getName() { return "execute_command"; }

    @Override
    public String getDescription() { return "在工作区中执行Shell命令"; }

    @Override
    public ToolCategory getCategory() { return ToolCategory.SHELL; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description", "要执行的命令"),
                "workdir", Map.of("type", "string", "description", "工作目录（可选）"),
                "timeout", Map.of("type", "integer", "description", "超时秒数", "default", 60)
            ),
            "required", new String[]{"command"}
        );
    }

    /**
     * 这条命令像不像 cmd 语法。
     *
     * <p>判据：用了 cmd 的内置命令 + `/x` 风格开关（`rmdir /s /q`、`del /f`、`xcopy /e`…），
     * 或者 cmd 独占的命令名（`dir`、`type`、`findstr`、`tasklist`、`taskkill`）。
     */
    private static boolean looksLikeCmd(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String c = command.trim().toLowerCase();
        boolean slashSwitch = c.matches(".*\\s/[a-z](\\s|$).*");
        if (slashSwitch && c.matches(".*\\b(rmdir|rd|del|erase|copy|move|xcopy|robocopy|attrib|icacls|net)\\b.*")) {
            return true;
        }
        return c.matches("^(dir|type|findstr|tasklist|taskkill|where|ver|set)\\b.*");
    }

    /** 有 PowerShell 7（pwsh）就用它：它支持 `&&`，比 5.1 更接近模型习惯。 */
    private static boolean pwshAvailable() {
        try {
            Process p = new ProcessBuilder("pwsh", "-NoProfile", "-Command", "exit 0")
                .redirectErrorStream(true).start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {        try {
            String command = getRequiredStringArg(arguments, "command");
            String workdir = getStringArg(arguments, "workdir", null);
            int timeout = arguments.containsKey("timeout") ? 
                ((Number) arguments.get("timeout")).intValue() : 300;

            log.info("执行命令: {}", command);

            ProcessBuilder pb = new ProcessBuilder();

            // 根据操作系统设置 shell。
            //
            // Windows 上原来用 `cmd /c`，模型写的是 Unix 风格命令（ls / cat / rm / pwd），
            // cmd 里根本没有这些 → 实测报 `'ls' 不是内部或外部命令`（退出码 1），
            // 后面还跟着 `系统找不到指定的文件`（退出码 2）。
            // 改成 PowerShell：ls/cat/rm/cp/mv/pwd 都是内置别名，能直接跑通。
            // 另外 PS 5.1 不认 `&&` 串联，这里顺手换成 `;`（模型很爱写 `ls && pwd`）。
            if (isWindows()) {
                // 【实测】模型两种写法都会用：
                //   ls -la            → PowerShell 的别名，用 cmd 会报"不是内部或外部命令"
                //   rmdir /s /q xxx   → cmd 的开关，用 PowerShell 会报"找不到与参数名称/q匹配的参数"
                // 所以按写法分流：带 cmd 风格开关的交给 cmd /c，其余交给 PowerShell。
                // 输出编码：Windows 控制台默认用 GBK 输出，Java 侧按 UTF-8 读会变乱码
                // （实测 `ls` 返回的"目录: …"就是乱码，模型根本读不懂）。让子进程直接吐 UTF-8。
                if (looksLikeCmd(command)) {
                    pb.command("cmd", "/c", "chcp 65001>nul & " + command);
                } else {
                    String shell = pwshAvailable() ? "pwsh" : "powershell";
                    String prefix = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $OutputEncoding=[System.Text.Encoding]::UTF8; ";
                    pb.command(shell, "-NoProfile", "-NonInteractive", "-Command", prefix + command.replace("&&", ";"));
                }
            } else {
                pb.command("sh", "-c", command);
            }

            if (workdir != null && !workdir.isBlank()) {
                pb.directory(new File(resolvePath(workdir)));
            } else if (currentWorkspace() != null) {
                // 未指定workdir时，默认在会话绑定的工作区中执行
                pb.directory(new File(currentWorkspace()));
            }

            pb.redirectErrorStream(false);

            Process process = pb.start();

            // 读取stdout
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (var reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.warn("读取stdout失败", e);
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (var reader = new BufferedReader(new java.io.InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.warn("读取stderr失败", e);
                }
            });

            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return error("命令执行超时（" + timeout + "秒）");
            }

            stdoutThread.join(5000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();
            String result = stdout.toString();
            String errors = stderr.toString();

            StringBuilder sb = new StringBuilder();
            if (!result.isEmpty()) {
                sb.append("输出:\n").append(result);
            }
            if (!errors.isEmpty()) {
                sb.append("错误:\n").append(errors);
            }
            sb.append("退出码: ").append(exitCode);

            if (exitCode == 0) {
                return success(sb.toString());
            } else {
                return error("命令执行失败（退出码: " + exitCode + "）\n" + sb);
            }

        } catch (Exception e) {
            return error("命令执行异常: " + e.getMessage());
        }
    }
}
