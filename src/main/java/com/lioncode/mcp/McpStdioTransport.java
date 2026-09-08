package com.lioncode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP stdio 传输
 *
 * 通过 ProcessBuilder 启动 MCP 服务器子进程，使用 LSP 风格帧
 * 在 stdin/stdout 上收发 JSON 消息：
 * 每条消息前加 "Content-Length: <字节数>\r\n\r\n"。
 *
 * 从 stdout 读、向 stdin 写；后台读线程负责按 id 匹配响应，
 * 异步通知（notifications / 服务器请求）忽略或记日志。
 */
public class McpStdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String serverName;
    private final McpServerConfig config;

    private Process process;
    private InputStream stdout;
    private OutputStream stdin;
    private Thread readerThread;
    private Thread stderrThread;
    private final Object writeLock = new Object();
    private volatile boolean closed = true;

    /** 等待中的请求：id -> 响应Future */
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public McpStdioTransport(String serverName, McpServerConfig config) {
        this.serverName = serverName;
        this.config = config;
    }

    @Override
    public void start() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(config.safeArgs());

        ProcessBuilder pb = new ProcessBuilder(command);
        this.process = pb.start();
        this.stdout = process.getInputStream();
        this.stdin = process.getOutputStream();
        this.closed = false;

        this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader-" + serverName);
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        this.stderrThread = new Thread(this::drainStderr, "mcp-stdio-stderr-" + serverName);
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();

        log.info("[{}] MCP stdio 进程已启动: {}", serverName, String.join(" ", command));
    }

    /**
     * 后台读循环：持续从 stdout 读取 LSP 帧并分发
     */
    private void readLoop() {
        try {
            while (!closed) {
                JsonNode msg = readMessage();
                if (msg == null) {
                    break; // EOF
                }
                handleMessage(msg);
            }
        } catch (Exception e) {
            if (!closed) {
                log.warn("[{}] MCP stdio 读循环异常: {}", serverName, e.getMessage());
            }
        } finally {
            closed = true;
            failAllPending("MCP进程已退出");
        }
    }

    /**
     * 分发一条消息：响应按 id 匹配；其余（通知/服务器请求）忽略
     */
    private void handleMessage(JsonNode msg) {
        if (msg == null) {
            return;
        }
        // JSON-RPC 响应：带 id 且包含 result 或 error
        if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
            long id = msg.get("id").asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) {
                future.complete(msg);
            } else {
                log.debug("[{}] 收到未匹配的响应 id={}", serverName, id);
            }
            return;
        }
        // 异步通知 / 服务器发起的请求：忽略，仅记日志
        String method = msg.has("method") ? msg.get("method").asText() : "unknown";
        log.debug("[{}] 忽略 MCP 异步消息: method={}", serverName, method);
    }

    /**
     * 从 stdout 读取一条 LSP 风格帧：先读 Content-Length 头，再读指定字节数
     */
    private JsonNode readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readLine();
            if (line == null) {
                return null; // EOF
            }
            if (line.isEmpty()) {
                break; // 头结束
            }
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                try {
                    contentLength = Integer.parseInt(
                        line.substring("Content-Length:".length()).trim());
                } catch (NumberFormatException e) {
                    log.warn("[{}] 非法的 Content-Length: {}", serverName, line);
                }
            }
        }
        if (contentLength < 0) {
            throw new IOException("缺少 Content-Length 头");
        }
        byte[] body = readNBytes(stdout, contentLength);
        return mapper.readTree(body);
    }

    /**
     * 从输入流读取一行（去掉结尾 \r\n）
     *
     * @return 行内容；EOF 且无数据时返回 null
     */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = stdout.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        if (b == -1 && sb.isEmpty()) {
            return null;
        }
        return sb.toString();
    }

    /**
     * 精确读取 n 个字节
     */
    private static byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) {
                throw new EOFException("流提前结束，期望 " + n + " 字节，已读 " + off + " 字节");
            }
            off += r;
        }
        return buf;
    }

    /**
     * 后台排空子进程 stderr，避免管道写满导致死锁
     */
    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.debug("[{}] stderr: {}", serverName, line);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("[{}] stderr 读取结束: {}", serverName, e.getMessage());
            }
        }
    }

    @Override
    public JsonNode sendRequest(JsonNode request, long timeoutMillis) throws Exception {
        long id = request.get("id").asLong();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeFrame(mapper.writeValueAsBytes(request));
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("MCP请求超时: " + request.path("method").asText(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("MCP请求失败: " + cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void sendNotification(JsonNode notification) throws Exception {
        writeFrame(mapper.writeValueAsBytes(notification));
    }

    /**
     * 写入一帧：Content-Length 头 + JSON 体
     */
    private void writeFrame(byte[] body) throws IOException {
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            stdin.write(header);
            stdin.write(body);
            stdin.flush();
        }
    }

    /**
     * 让所有等待中的请求异常结束
     */
    private void failAllPending(String reason) {
        IOException ex = new IOException(reason);
        pending.values().forEach(f -> f.completeExceptionally(ex));
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (process != null) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        failAllPending("传输已关闭");
        log.info("[{}] MCP stdio 传输已关闭", serverName);
    }
}
