package com.lioncode.core.plugin.tool.shell;

import com.lioncode.core.plugin.TerminalPlugin;
import com.lioncode.core.plugin.tool.AbstractToolPlugin;
import com.lioncode.core.plugin.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

/**
 * Shell命令执行工具（常驻终端）。
 *
 * <p>它不是"每条命令起一个进程"，而是把这个工作区的一个**长期活着的 PowerShell**
 * 当终端用：命令流里 {@code cd}、变量、函数都会留到下一次调用
 * （实现见 {@link PersistentShell}）。所以模型可以像人在终端里一样一条条往下做，
 * 而不是每条命令都在一个全新的、忘了刚才做过什么的进程里跑。
 */
@Component
public class ShellExecuteTool extends AbstractToolPlugin {

    private static final Logger log = LoggerFactory.getLogger(ShellExecuteTool.class);

    private final PersistentShell terminal;
    private final TerminalPlugin terminalPlugin;

    public ShellExecuteTool(PersistentShell terminal, TerminalPlugin terminalPlugin) {
        this.terminal = terminal;
        this.terminalPlugin = terminalPlugin;
    }

    @Override
    public String getId() { return "tool.shell.execute"; }

    @Override
    public String getName() { return "execute_command"; }

    @Override
    public String getDescription() {
        return "在常驻终端里执行命令（同一工作区共用一个持续运行的 PowerShell 会话，"
            + "cd、变量、函数会保留到下一次调用）";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.SHELL; }

    @Override
    protected Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description", "要执行的命令"),
                "workdir", Map.of("type", "string",
                    "description", "工作目录（可选；给了就先切过去，之后的命令留在这个目录）"),
                "timeout", Map.of("type", "integer", "description", "超时秒数", "default", 60)
            ),
            "required", new String[]{"command"}
        );
    }

    /**
     * 把常见的 Unix 写法翻成 PowerShell 等价写法。
     *
     * <p>实测：模型写 {@code echo hello && ls -la}，PowerShell 报
     * "Get-ChildItem : 找不到与参数名称"la"匹配的参数"。这类 Unix 开关在 PowerShell 里
     * **永远不可能合法**，所以按模式翻译是安全的（只翻确定的几种，不做通用猜测）。
     */
    static String unixToPowerShell(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String c = command;
        // ls -la / ls -l / ls -al / ll → 列全部文件
        c = c.replaceAll("\\bll\\b", "ls -Force");
        c = c.replaceAll("\\bls\\s+-[a-zA-Z]*l[a-zA-Z]*(\\s|$)", "ls -Force$1");
        c = c.replaceAll("\\bls\\s+-a(\\s|$)", "ls -Force$1");
        // rm -rf / rm -f / rm -r
        c = c.replaceAll("\\brm\\s+-rf\\b", "Remove-Item -Recurse -Force");
        c = c.replaceAll("\\brm\\s+-fr\\b", "Remove-Item -Recurse -Force");
        c = c.replaceAll("\\brm\\s+-r\\b", "Remove-Item -Recurse");
        c = c.replaceAll("\\brm\\s+-f\\b", "Remove-Item -Force");
        // cp -r / mv -f
        c = c.replaceAll("\\bcp\\s+-r\\b", "Copy-Item -Recurse");
        c = c.replaceAll("\\bmv\\s+-f\\b", "Move-Item -Force");
        // mkdir -p
        c = c.replaceAll("\\bmkdir\\s+-p\\b", "New-Item -ItemType Directory -Force");
        c = c.replaceAll("\\bmkdir\\s+-p\\s+", "New-Item -ItemType Directory -Force -Path ");
        // grep / touch / which / ps aux
        c = c.replaceAll("\\bgrep\\b", "Select-String");
        c = c.replaceAll("\\btouch\\b", "New-Item -ItemType File -Force");
        c = c.replaceAll("\\bwhich\\b", "Get-Command");
        c = c.replaceAll("\\bps\\s+aux\\b", "Get-Process");
        // head/tail 的 Unix 用法（head -n 5 file）
        c = c.replaceAll("\\bhead\\s+-n\\s+(\\d+)\\s+", "Get-Content -TotalCount $1 ");
        c = c.replaceAll("\\btail\\s+-n\\s+(\\d+)\\s+", "Get-Content -Tail $1 ");
        return c;
    }

    /**
     * 这条命令像不像 cmd 语法。
     *
     * <p>判据：用了 cmd 的内置命令 + `/x` 风格开关（`rmdir /s /q`、`del /f`、`xcopy /e`…），
     * 或者 cmd 独占的命令名（`dir`、`type`、`findstr`、`tasklist`、`taskkill`）。
     */
    static boolean looksLikeCmd(String command) {
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

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String command = getRequiredStringArg(arguments, "command");
            String workdir = getStringArg(arguments, "workdir", null);

            // 【谁说了算】模型给的 timeout 只是它的"期望"，用户在终端插件里设的上限是"规定"。
            // 取小值 —— 否则模型随手写个 99999 就把用户设的限制绕过去了。
            int requested = getIntArg(arguments, "timeout", 0);
            int timeout = terminalPlugin.clampTimeout(requested);
            // 输出上限同理：0 = 不限制（老行为），>0 时超出的部分直接在读取侧丢掉
            int maxOutputBytes = terminalPlugin.maxOutputBytes();

            log.info("执行命令: {}", command);

            // 目标目录先解析+检查，别把不存在的目录塞进终端（那只会回一句看不懂的报错）
            String dir = null;
            if (workdir != null && !workdir.isBlank()) {
                dir = resolvePath(workdir);
                if (!new File(dir).isDirectory()) {
                    return error("工作目录不存在: " + dir
                        + "（先 create_directory 建出来，或检查路径）");
                }
            }

            String prepared = command;
            if (isWindows()) {
                // 【实测】模型两种写法都会用：
                //   ls -la            → PowerShell 别名，用 cmd 会报"不是内部或外部命令"
                //   rmdir /s /q xxx   → cmd 开关，用 PowerShell 会报"找不到与参数名称/q匹配的参数"
                // 所以按写法分流：带 cmd 风格开关的交给 `cmd /c`（在常驻终端里跑，不另起 shell），
                // 其余按 PowerShell 走，并把 Unix 写法翻译过来。
                if (looksLikeCmd(prepared)) {
                    prepared = "cmd /c '" + prepared.replace("'", "''") + "'";
                } else {
                    // PowerShell 5.1 不认 `&&`：模型很爱写 `ls && pwd`，换成 `;`
                    String translated = unixToPowerShell(prepared).replace("&&", ";");
                    if (!translated.equals(command)) {
                        log.info("命令含 Unix 写法，已改写为 PowerShell: {} → {}", command, translated);
                    }
                    prepared = translated;
                }
            }

            String key = currentWorkspace() == null ? "default" : currentWorkspace();
            PersistentShell.RunResult r = terminal.run(key, prepared, dir, timeout, maxOutputBytes);

            if (r.errorText() != null) {
                return error(r.errorText());
            }

            StringBuilder sb = new StringBuilder();
            if (!r.output().isEmpty()) {
                sb.append("输出:\n").append(r.output()).append("\n");
            } else {
                sb.append("（这条命令没有输出）\n");
            }
            // 【必须明确告诉模型"被截断了"】不提示的话它会把"没看到"当成"不存在"，
            // 然后基于不完整的信息下结论（"日志里没有报错"）。说清了它才知道换个更精确的查法。
            if (r.truncated()) {
                sb.append("⚠ 输出已截断：这条命令的输出超过了终端设置的上限（").append(maxOutputBytes)
                  .append(" 字节），后面的内容没有带回来。要看全就用更精确的命令")
                  .append("（例如 Select-String 过滤、Get-Content -TotalCount 只看前几行），")
                  .append("或在设置 → 插件 → 终端里调大「最大输出字节数」。\n");
            }
            if (r.cwd() != null) {
                sb.append("当前目录: ").append(r.cwd()).append("\n");
            }
            Integer code = r.exitCode();
            sb.append("退出码: ").append(code != null ? code : (r.ok() ? 0 : 1));

            if (r.ok() && (code == null || code == 0)) {
                return success(sb.toString());
            }
            // 【实测】退出码非 0 但明明有输出时（例如 git 在非仓库目录报 128、grep 没匹配到），
            // 一律写成"执行失败"会让模型以为命令根本没跑。这里把"有没有输出"说清楚，
            // 常见的 128 / 1 再补一句提示，它下一轮就能改对。
            StringBuilder fail = new StringBuilder();
            fail.append("命令以退出码 ").append(code != null ? code : "非0").append(" 结束");
            fail.append(r.output().isEmpty() ? "（没有任何输出）" : "（**有输出**，见下）");
            if (code != null && code == 128) {
                fail.append("；128 是 git 的 fatal：多半是当前目录不是 git 仓库（先 git_init，或用 path 指定仓库目录）");
            }
            fail.append('\n').append(sb);
            return error(fail.toString());
        } catch (Exception e) {
            return error("命令执行异常: " + e.getMessage());
        }
    }
}
