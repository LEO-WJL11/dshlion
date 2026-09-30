package com.lioncode.core.plugin.tool.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 无头浏览器：用系统里**已经装好的** Edge / Chrome 打开页面，把「渲染后的 DOM」抓回来。
 *
 * <p>为什么走浏览器而不是搜索 API：用户明确要求「网络搜索改成用无头浏览器搜，不要 api」——
 * 不要密钥、不要注册、不要配额、不用管某家 API 哪天改条款；浏览器把 JS 跑完再把 DOM 交给我们。
 *
 * <p>实现走 Chrome/Edge 自带的 {@code --dump-dom}：**一次调用一个进程，跑完自己退出**。
 * 比连 CDP/WebSocket 省事得多，也不会留下常驻进程（端口、句柄、僵尸进程都免了）。
 *
 * <p>找不到浏览器时 {@link #available()} 为 false，调用方自己去退到"直接抓 HTML"那条路。
 * 想指定浏览器：设环境变量 {@code LIONBOX_BROWSER=<可执行文件路径>}。
 */
@Component
public class HeadlessBrowser {

    private static final Logger log = LoggerFactory.getLogger(HeadlessBrowser.class);

    /** 页面里 JS 跑多久（毫秒）再 dump —— 给结果页留足渲染时间 */
    private static final int VIRTUAL_BUDGET_MS = 6000;

    private static final List<String> CANDIDATES = List.of(
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        System.getProperty("user.home", ".") + "\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe"
    );

    private final String exe;
    private final String why;

    public HeadlessBrowser() {
        String found = null;
        String env = System.getenv("LIONBOX_BROWSER");
        if (env != null && !env.isBlank() && new File(env.trim()).isFile()) {
            found = env.trim();
        }
        if (found == null) {
            for (String p : CANDIDATES) {
                if (new File(p).isFile()) {
                    found = p;
                    break;
                }
            }
        }
        if (found == null) {
            for (String name : new String[] {"msedge", "chrome", "chromium"}) {
                String p = which(name);
                if (p != null) {
                    found = p;
                    break;
                }
            }
        }
        this.exe = found;
        this.why = found != null ? ""
            : "没找到 Edge/Chrome（设环境变量 LIONBOX_BROWSER=<路径> 可指定）";
        if (found != null) {
            log.info("无头浏览器就绪: {}", found);
        } else {
            log.warn("{}", why);
        }
    }

    public boolean available() {
        return exe != null;
    }

    /** 浏览器可执行文件路径（没装就是空串），给工具结果里如实说明用 */
    public String exePath() {
        return exe == null ? "" : exe;
    }

    /** 没找到浏览器时的原因，写给用户看 */
    public String unavailableReason() {
        return why;
    }

    /**
     * 打开 url 并返回**渲染后**的 DOM（含执行完的 JS）。
     *
     * @return DOM 文本；浏览器没装、超时、页面空白都返回 null，由调用方决定退路
     */
    public String dumpDom(String url, int timeoutSeconds) {
        if (exe == null || url == null || url.isBlank()) {
            return null;
        }
        Path profile = null;
        Process p = null;
        try {
            profile = Files.createTempDirectory("lionbox-headless-");
            List<String> cmd = new ArrayList<>(List.of(exe,
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-sync",
                "--disable-background-networking",
                "--mute-audio",
                "--hide-scrollbars",
                "--user-data-dir=" + profile,
                "--virtual-time-budget=" + VIRTUAL_BUDGET_MS,
                "--dump-dom",
                url));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            p = pb.start();

            // stdout 要一边读一边等：--dump-dom 把整页 DOM 打到 stdout，
            // 读满了却没人读的话浏览器会卡在写上面（死锁），所以放在线程里读。
            final Process proc = p;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> drain(proc.getInputStream(), buf, Integer.MAX_VALUE),
                "lionbox-headless-reader");
            reader.setDaemon(true);
            reader.start();
            // 【坑】stderr 也得抽干：Edge 一启动就往 stderr 写一堆日志（实测 5KB+），
            // 管道写满它就阻塞在那里、永远不退出 —— 表现是"超时被收掉、没吐出内容"。
            // 只留一小段当诊断日志，多了就不存。
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errReader = new Thread(() -> drain(proc.getErrorStream(), err, 4096),
                "lionbox-headless-err");
            errReader.setDaemon(true);
            errReader.start();

            boolean done = p.waitFor(Math.max(5, timeoutSeconds), TimeUnit.SECONDS);
            if (!done) {
                log.warn("无头浏览器超时（{} 秒），收掉进程：{}", timeoutSeconds, url);
                killTree(p);
            }
            reader.join(3000);
            errReader.join(1000);
            String html = decode(buf.toByteArray());
            if (html == null || html.isBlank()) {
                String e = decode(err.toByteArray());
                log.warn("无头浏览器没吐出内容（exit={}）：{}{}", done ? p.exitValue() : "被收掉", url,
                    e == null || e.isBlank() ? "" : "；stderr: " + e.strip().split("\\R")[0]);
                return null;
            }
            return html;
        } catch (Exception e) {
            log.warn("无头浏览器失败: {}", e.getMessage());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                killTree(p);
            }
            deleteQuietly(profile);
        }
    }

    // ------------------------------------------------------------------

    /** 连子进程一起收掉（浏览器会 fork 一堆渲染进程，只 kill 父进程会留一堆僵尸） */
    private void killTree(Process p) {
        try {
            long pid = p.pid();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
            } else {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
            }
        } catch (Exception ignored) {
            // 收不掉就算了
        }
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
            // 同上
        }
    }

    /** 把一条流读干（最多留 max 字节）；不读干的话子进程会卡在写上面 */
    private void drain(InputStream in, ByteArrayOutputStream sink, int max) {
        try (InputStream stream = in) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = stream.read(b)) > 0) {
                if (sink.size() < max) {
                    sink.write(b, 0, Math.min(n, max - sink.size()));
                }
            }
        } catch (Exception ignored) {
            // 进程被杀时读会抛，正常
        }
    }

    /** DOM 是 UTF-8；万一不是（老机器代码页），退到 GBK / Latin-1，绝不因为编码把结果丢了 */
    private String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ignored) {
            // 不是合法 UTF-8，往下退
        }
        try {
            return new String(bytes, "GBK");
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                    // 浏览器还可能占着个别文件，留着也无所谓（在系统临时目录里）
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    private String which(String name) {
        try {
            Process p = new ProcessBuilder("where", name).redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String line : out.split("\\R")) {
                if (!line.isBlank() && new File(line.trim()).isFile()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
            // 没有就没有
        }
        return null;
    }
}
