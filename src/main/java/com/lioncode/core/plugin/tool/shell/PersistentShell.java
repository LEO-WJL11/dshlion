package com.lioncode.core.plugin.tool.shell;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 常驻 Shell 会话（"持续运行的终端"）。
 *
 * <p><b>为什么要有这个类</b>：以前 {@code execute_command} 是"一条命令起一个进程"——
 * {@code powershell -Command "..."} 跑完就退出。所以 {@code cd} 是白做的，{@code $env:X=1}
 * 下一轮就没、函数/别名更留不下来；用户看到的现象是"这不是终端，它每次都新开一个"。
 * 现在改成：**同一个工作区共用一个长期活着的 PowerShell 进程**，命令从它的 stdin 递进去，
 * {@code cd}、变量、函数、别名都真的会留在下一次调用里（跟真人开的终端一样）。
 *
 * <p><b>协议</b>（三个坑都在这里解决）：
 * <ol>
 *   <li><b>多行脚本</b>：PowerShell 5.1 的 {@code -Command -} 是**按行**读 stdin 的，
 *       直接喂 {@code if (...) \{} 换行 {@code }} 会被拆散、卡住。所以命令先做 UTF-8
 *       Base64，整条作为一行 ASCII 送进去（{@code [Convert]::FromBase64String} 再
 *       {@code Invoke-Expression}），多行脚本就当成一整段脚本执行。</li>
 *   <li><b>中文乱码</b>：PS 5.1 的 stdin/stdout 走的是**系统 ANSI/OEM 代码页**（中文机器 = GBK），
 *       用 UTF-8 写命令进去，中文路径在进去之前就已经乱了。Base64 全程 ASCII，从根上绕过；
 *       输出侧在会话建立时设一次 {@code [Console]::OutputEncoding = UTF8}。</li>
 *   <li><b>哪一段输出属于哪条命令</b>：每条命令跑完由 shell 自己打一行唯一哨兵
 *       （{@code __LIONBOX_DONE_<随机>__ ok=… code=… cwd=…}），读到哨兵就收工。
 *       哨兵放在 try/catch 里，命令抛异常也会打出来，不会把调用方吊死。</li>
 * </ol>
 *
 * <p>超时：命令卡住（等输入、死循环）时不能像以前那样只杀子进程——那样这个会话本身也废了。
 * 现在的做法是连**整个 shell 会话一起杀掉并重建**，并在结果里明确告诉模型"终端被重启了，
 * 之前 cd 的目录和变量没了"，它下一轮就不会接着用不存在的状态。
 */
@Component
public class PersistentShell {

    private static final Logger log = LoggerFactory.getLogger(PersistentShell.class);

    /** 哨兵前缀；后面拼一段随机数，避免和命令自身的输出撞车 */
    private static final String MARK = "__LIONBOX_DONE_";

    /** 会话建立握手超时（秒） */
    private static final int STARTUP_TIMEOUT = 20;

    /** 输出编码设置（会话第一条命令） */
    private static final String INIT = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
        + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
        + "$PSDefaultParameterValues['Out-File:Encoding']='utf8'";

    /** 工作区 → 会话 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 一次命令执行的结果。
     *
     * @param output    命令输出（已去掉哨兵行；stdout 与 stderr 合并，跟真终端一样）
     * @param exitCode  退出码（拿不到时为 null）
     * @param ok        shell 报的 {@code $?}
     * @param cwd       执行完之后终端所在目录
     * @param timedOut  是否超时（此时 shell 已被重启）
     * @param restarted 是否重启过 shell（超时/被 exit 杀掉）
     */
    public record RunResult(String output, Integer exitCode, boolean ok, String cwd,
                            boolean timedOut, boolean restarted, String errorText) {
    }

    /** 会话空闲多久之后回收（毫秒）；10 分钟没命令就把进程关掉，别白占内存 */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * 在常驻会话里执行一条命令。
     *
     * @param key       会话键（用工作区路径；同一个工作区 = 同一个终端）
     * @param command   已经翻译过的命令（Unix 写法 → PowerShell 写法在调用方完成）
     * @param workdir   这条命令要求的目录（null = 沿用终端当前目录）
     * @param timeoutSec 超时秒数
     */
    public RunResult run(String key, String command, String workdir, int timeoutSec) {
        String sessionKey = (key == null || key.isBlank()) ? "default" : key;
        int timeout = timeoutSec <= 0 ? 300 : timeoutSec;
        Session session;
        try {
            session = acquire(sessionKey);
        } catch (Exception e) {
            return new RunResult("", null, false, null, false, false,
                "无法启动常驻终端: " + e.getMessage());
        }
        return exec(session, sessionKey, command, workdir, timeout);
    }

    /** 真正执行：写命令 → 等哨兵 → 收输出。 */
    private RunResult exec(Session session, String sessionKey, String command,
                           String workdir, int timeout) {
        String token = MARK + Long.toHexString(System.nanoTime() & 0xffffffffL) + "__";
        String payload = buildPayload(command, workdir, token);

        synchronized (session) {
            try {
                session.lines.clear();          // 丢掉上一条命令残留的输出
                session.stdin.write(encode(sessionKey, payload));
                session.stdin.newLine();
                session.stdin.flush();
            } catch (IOException e) {
                sessions.remove(sessionKey, session);
                session.destroy();
                return new RunResult("", null, false, null, false, true,
                    "终端会话已失效（写入失败），已重启: " + e.getMessage());
            }

            StringBuilder out = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            while (true) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    // 命令卡住：整个会话一起杀，重建一个干净终端
                    sessions.remove(sessionKey, session);
                    session.destroy();
                    return new RunResult(out.toString(), null, false, null, true, true,
                        "命令执行超时（" + timeout + "秒），已强制重启终端"
                            + "（超时通常是在等输入，例如 pause/read-host/set /p）");
                }
                String line;
                try {
                    line = session.lines.poll(Math.min(remain, 200), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    // 派发层（AgentLoop）等不及了会 shutdownNow()，把跑工具的线程打断。
                    // 这时候**必须连终端一起重启** —— 否则那条卡住的命令还在 shell 里跑着，
                    // 后面的命令会一直排在它屁股后面（用户看到的是"工具突然都变慢了"）。
                    sessions.remove(sessionKey, session);
                    session.destroy();
                    Thread.currentThread().interrupt();
                    return new RunResult(out.toString(), null, false, null, true, true,
                        "命令被上层中断（等太久了），已强制重启终端；"
                            + "之前 cd 的目录和变量没了，需要的话重新 cd 一次");
                }
                if (line == null) {
                    if (!session.process.isAlive()) {
                        sessions.remove(sessionKey, session);
                        return new RunResult(out.toString(), null, false, null, false, true,
                            "终端进程已退出（命令里可能有 exit）—— 下次调用会自动重开一个终端");
                    }
                    continue;
                }
                int idx = line.indexOf(token);
                if (idx < 0) {
                    out.append(line).append('\n');
                    continue;
                }
                // 哨兵可能和最后一行输出挤在同一行（命令用了 -NoNewline），前半截也要留着
                if (idx > 0) {
                    out.append(line, 0, idx);
                }
                String tail = line.substring(idx + token.length()).trim();
                boolean ok = tail.contains("ok=True");
                Integer code = parseCode(tail);
                String cwd = parseCwd(tail);
                if (cwd != null) {
                    session.cwd = cwd;
                }
                session.lastUsed = System.currentTimeMillis();
                String text = strip(out);
                if (text.startsWith("__LIONBOX_ERR__")) {
                    text = text.substring("__LIONBOX_ERR__".length()).trim();
                }
                return new RunResult(text, code, ok, cwd, false, false, null);
            }
        }
    }

    /** 关掉某个工作区的终端（用户主动要求重开时用） */
    public boolean close(String key) {
        Session s = sessions.remove(key == null || key.isBlank() ? "default" : key);
        if (s == null) {
            return false;
        }
        s.destroy();
        return true;
    }

    /** 当前活着的终端有哪些（诊断用） */
    public List<String> activeShells() {
        return new ArrayList<>(sessions.keySet());
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(Session::destroy);
        sessions.clear();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private Session acquire(String key) throws IOException {
        Session existing = sessions.get(key);
        if (existing != null) {
            if (existing.process.isAlive()
                    && System.currentTimeMillis() - existing.lastUsed < IDLE_TIMEOUT_MS) {
                return existing;
            }
            sessions.remove(key);
            existing.destroy();
        }
        Session created = new Session(startProcess(key));
        Session prev = sessions.putIfAbsent(key, created);
        if (prev != null) {
            created.destroy();
            return prev;
        }
        // 握手：确认这个 shell 真的能收命令（不然第一条真命令会白等到超时）。
        // 顺手把输出编码设成 UTF-8，中文路径/中文输出才不会变成乱码。
        RunResult hello = exec(created, key, INIT, null, STARTUP_TIMEOUT);
        if (hello.errorText() != null || !hello.ok()) {
            sessions.remove(key);
            created.destroy();
            throw new IOException(hello.errorText() != null
                ? hello.errorText()
                : "终端启动后没有回应（" + created.shellName + "）");
        }
        log.info("常驻终端已启动: {}（{}）", key, created.shellName);
        return created;
    }

    private Process startProcess(String key) throws IOException {
        List<String> cmd = new ArrayList<>();
        String shell = shellExecutable();
        cmd.add(shell);
        cmd.add("-NoLogo");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-Command");
        cmd.add("-");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 错误流并进标准输出：真终端就是这样，模型看到的顺序也和实际发生的一致
        pb.redirectErrorStream(true);
        File dir = new File(key.equals("default") ? System.getProperty("user.home") : key);
        if (dir.isDirectory()) {
            pb.directory(dir);
        }
        return pb.start();
    }

    /** 有 PowerShell 7（pwsh）就用它；没有就用系统自带的 Windows PowerShell 5.1。 */
    private static String shellExecutable() {
        if (isWindows()) {
            try {
                Process probe = new ProcessBuilder("pwsh", "-NoLogo", "-NoProfile", "-Command", "exit 0")
                    .redirectErrorStream(true).start();
                probe.getInputStream().readAllBytes();
                if (probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                    return "pwsh";
                }
            } catch (Exception ignore) {
                // 没有 pwsh，继续用 powershell
            }
            return "powershell";
        }
        return "sh";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 单个常驻会话 */
    private static final class Session {
        final Process process;
        final BufferedWriter stdin;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final String shellName;
        volatile String cwd;
        volatile long lastUsed = System.currentTimeMillis();

        Session(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.US_ASCII));
            String name = "shell";
            try {
                name = process.info().command().orElse("shell");
            } catch (Exception ignore) {
                // 拿不到就算了
            }
            this.shellName = name;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        lines.offer(line);
                    }
                } catch (IOException e) {
                    log.debug("常驻终端读取结束: {}", e.getMessage());
                }
            }, "lionbox-shell-reader");
            reader.setDaemon(true);
            reader.start();
        }

        void destroy() {
            try {
                process.destroyForcibly();
            } catch (Exception ignore) {
                // 已经死了
            }
        }
    }

    /**
     * 组装要送进 shell 的脚本。
     *
     * <p>哨兵放进 try/catch 的**两个分支**里：命令正常结束在 try 里打，抛出终止性错误在 catch 里打。
     * 这样无论命令怎么炸，调用方都能收到哨兵，不会白等到超时。
     */
    private static String buildPayload(String command, String workdir, String token) {
        StringBuilder sb = new StringBuilder();
        sb.append("try {\n");
        if (workdir != null && !workdir.isBlank()) {
            sb.append("Set-Location -LiteralPath '").append(workdir.replace("'", "''")).append("'\n");
        }
        sb.append(command).append("\n");
        sb.append("Write-Output (\"").append(token)
          .append(" ok=$? code=$LASTEXITCODE cwd=\" + (Get-Location).Path)\n");
        sb.append("} catch {\n");
        sb.append("Write-Output (\"__LIONBOX_ERR__\" + $_.Exception.Message)\n");
        sb.append("Write-Output (\"").append(token)
          .append(" ok=False code=$LASTEXITCODE cwd=\" + (Get-Location).Path)\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Base64 成**一行 ASCII** 再交给 {@code Invoke-Expression}。
     *
     * <p>代价是报错时行号会指向解码后的脚本，但换来的是"多行脚本能跑 + 中文不乱"，
     * 这两点在实测里都是致命的，行号不重要。
     */
    private static String encode(String key, String payload) {
        String b64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "$__lionbox_c=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64
            + "')); Invoke-Expression $__lionbox_c";
    }

    private static Integer parseCode(String tail) {
        int i = tail.indexOf("code=");
        if (i < 0) {
            return null;
        }
        String rest = tail.substring(i + 5).trim();
        int end = 0;
        while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            return Integer.valueOf(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseCwd(String tail) {
        int i = tail.indexOf("cwd=");
        if (i < 0) {
            return null;
        }
        String cwd = tail.substring(i + 4).trim();
        return cwd.isEmpty() ? null : cwd;
    }

    /** 去掉结尾多余空行，别让工具结果里全是空行 */
    private static String strip(StringBuilder sb) {
        String s = sb.toString();
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
