package com.lioncode.model.runtime;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 盒子本地模型运行时（llama.cpp）的惰性加载与进程管理。
 *
 * 为什么需要它
 * ------------------------------------------------------------------
 * 之前是启动器（launcher.ps1）在开机时就把 8.9GB 的 GGUF 拉进显存，
 * 哪怕用户只是想看看界面、或者根本打算用自己填的 API，也得先等 30~90 秒模型加载。
 *
 * 现在的规则：
 *   1. 应用启动时**不加载任何模型**；
 *   2. 用户发出第一条消息时才拉起本地运行时（{@link #ensureRunning()}）；
 *   3. 如果用户配置的是自己的 API（非本机回环端点），
 *      {@link #manages(String)} 返回 false，本地运行时**永远不会被拉起来**。
 *
 * 为什么放在这一层
 * ------------------------------------------------------------------
 * 真正决定"要不要连本地"的地方只有一个：适配器准备发 HTTP 请求的那一刻。
 * 所以钩子挂在 {@code OpenAICompatibleAdapter} 里，所有调用路径
 * （同步、流式、标题生成）都会自然经过，不需要在每个入口重复判断。
 *
 * 并发安全：多个请求同时到达时，只有第一个真正去拉进程，其余等待同一个结果。
 */
@Component
public class LocalModelRuntime {

    private static final Logger log = LoggerFactory.getLogger(LocalModelRuntime.class);

    @Value("${lionbox.runtime.host:127.0.0.1}")
    private String host;

    @Value("${lionbox.runtime.port:8788}")
    private int port;

    @Value("${lionbox.runtime.model-file:lion-merged-Q8_0.gguf}")
    private String modelFile;

    @Value("${lionbox.runtime.model-name:MiMo-V2.6-Distill-Qwen-9B}")
    private String modelName;

    /** 运行时可执行文件；留空则自动在程序目录下找 runtime-vulkan/llama-server.exe */
    @Value("${lionbox.runtime.exe:}")
    private String exePath;

    /** 模型文件完整路径；留空则自动在程序目录下找 model-file */
    @Value("${lionbox.runtime.model-path:}")
    private String modelPath;

    @Value("${lionbox.runtime.ctx-size:262144}")
    private int ctxSize;

    /** KV cache 量化类型（q4_0 / q8_0 / f16 …）；空或 f16 表示不量化 */
    @Value("${lionbox.runtime.kv-cache-type:q4_0}")
    private String kvCacheType;

    /** 是否启用 flash attention（量化 KV 必须开，否则 llama.cpp 拒绝该类型） */
    @Value("${lionbox.runtime.flash-attn:true}")
    private boolean flashAttn;

    /**
     * 并行 slot 数，默认 1。
     * llama-server 会把 -c 平均分给各 slot，默认多 slot 意味着 256K 实际只剩几万，
     * 本应用同一时刻只跑一条主对话，所以给 1 个 slot 独占整个上下文。
     */
    @Value("${lionbox.runtime.parallel-slots:1}")
    private int parallelSlots;

    /** 采样温度。llama-server 默认 0.80，实测该值下模型会跑飞，故取低值 */
    @Value("${lionbox.runtime.temperature:0.2}")
    private double temperature;

    @Value("${lionbox.runtime.top-p:0.9}")
    private double topP;

    /** 重复惩罚；llama-server 默认 1.00 = 关闭，是跑飞的直接原因之一 */
    @Value("${lionbox.runtime.repeat-penalty:1.05}")
    private double repeatPenalty;

    @Value("${lionbox.runtime.repeat-last-n:256}")
    private int repeatLastN;

    /** 单次最多生成多少 token（默认 -1 = 无限，会把 256K 上下文写满） */
    @Value("${lionbox.runtime.max-predict:4096}")
    private int maxPredict;

    @Value("${lionbox.runtime.start-timeout-seconds:240}")
    private int startTimeoutSeconds;

    /** 是否启用惰性加载（关掉就退回"启动即加载"的老行为） */
    @Value("${lionbox.runtime.lazy-load:true}")
    private boolean lazyLoad;

    // ---- 模型自动下载（首次运行的"装完即用"）----
    // 安装包不再内置 8.9GB 权重，改为首次用到时从 ModelScope 拉取。
    // 下载地址走 ModelScope 的 repo 接口（会 302 到 CDN），不需要装 Python / modelscope CLI。
    @Value("${lionbox.runtime.model-repo:lionnezha/lion-models}")
    private String modelRepo;

    @Value("${lionbox.runtime.model-revision:master}")
    private String modelRevision;

    /** 关掉它就只认本地已有的权重（离线/内网部署用） */
    @Value("${lionbox.runtime.auto-download:true}")
    private boolean autoDownload;

    /** 下载完至少要这么大才算是个模型（防把错误页/半截文件当成权重） */
    @Value("${lionbox.runtime.min-model-bytes:1000000000}")
    private long minModelBytes;

    private volatile long downloadBytes = 0;
    private volatile long downloadTotal = -1;

    private final Object startLock = new Object();
    private volatile Process process;
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final AtomicReference<String> phase = new AtomicReference<>("idle");

    // ------------------------------------------------------------------
    // 对外状态
    // ------------------------------------------------------------------

    public String baseUrl() {
        return "http://" + host + ":" + port + "/v1";
    }

    public String modelName() {
        return modelName;
    }

    public int port() {
        return port;
    }

    public boolean isLazyLoad() {
        return lazyLoad;
    }

    /**
     * 给定端点是否由本组件管理（决定要不要去拉本地进程）。
     *
     * 只有「回环地址 + 端口正好是本运行时的端口」才算。
     * 用户填自己的 API（哪怕是 http://localhost:11434，Ollama 之类）不会被当成我们的运行时，
     * 否则会去拉起一个根本用不上的 llama.cpp，白占显存。
     */
    public boolean manages(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String s = baseUrl.trim().toLowerCase();
        boolean loopback = s.contains("://127.0.0.1") || s.contains("://localhost")
            || s.contains("://[::1]") || s.contains("://0.0.0.0");
        if (!loopback) {
            return false;
        }
        try {
            URI u = URI.create(s);
            int p = u.getPort();
            if (p < 0) {
                p = "https".equals(u.getScheme()) ? 443 : 80;
            }
            return p == port;
        } catch (Exception e) {
            return s.contains(":" + port);
        }
    }

    public boolean isRunning() {
        return healthy();
    }

    /** 当前状态快照，给前端和 /api/runtime/local 用 */
    public Status status() {
        return new Status(
            resolvesExe() != null,
            resolvesModel() != null,
            healthy(),
            "starting".equals(phase.get()),
            phase.get(),
            host,
            port,
            modelName,
            modelFile,
            resolvesExe() == null ? "" : resolvesExe().toString(),
            resolvesModel() == null ? "" : resolvesModel().toString(),
            logFile() == null ? "" : logFile().toString(),
            lastError.get(),
            downloadBytes,
            downloadTotal
        );
    }

    public record Status(
        boolean runtimeInstalled,
        boolean modelInstalled,
        boolean running,
        boolean starting,
        String phase,
        String host,
        int port,
        String modelName,
        String modelFile,
        String exePath,
        String resolvedModelPath,
        String logPath,
        String lastError,
        /** 自动下载进度：已下字节 / 总字节（-1 = 未知），phase=downloading 时前端可以显示进度 */
        long downloadBytes,
        long downloadTotal
    ) {}

    // ------------------------------------------------------------------
    // 惰性启动
    // ------------------------------------------------------------------

    /**
     * 确保本地模型已经就绪；已经就绪时立刻返回（不重复拉进程）。
     *
     * @return 是否就绪
     * @throws IllegalStateException 拉起失败（附带日志尾巴，方便用户自查）
     */
    public boolean ensureRunning() {
        if (healthy()) {
            return true;
        }
        synchronized (startLock) {
            // 双检：等锁期间可能已经被别人拉起来了
            if (healthy()) {
                return true;
            }
            Path exe = resolvesExe();
            Path model = resolvesModel();
            if (exe != null && model == null) {
                // 权重不在 → 首次运行自动下载（安装包不内置 8.9GB 权重）
                model = downloadModelIfAllowed();
            }
            if (exe == null || model == null) {
                lastError.set("找不到本地模型运行时或模型文件");
                phase.set("missing");
                log.error("本地模型运行时不可用: exe={}, model={}", exe, model);
                throw new IllegalStateException(
                    "本地模型运行时不可用：请确认程序目录下有 runtime-vulkan\\llama-server.exe 与 "
                        + modelFile + "；或切换到「自定义 API」模式。"
                        + (autoDownload ? "" : "（当前已关闭自动下载）"));
            }
            starting.set(true);
            try {
                // 先试核显加速，失败再退回纯 CPU
                if (spawn(exe, model, 999, startTimeoutSeconds)) {
                    phase.set("ready");
                    lastError.set("");
                    return true;
                }
                log.warn("GPU 加速启动失败，改用纯 CPU 重试（速度较慢）");
                killProcess();
                if (spawn(exe, model, 0, Math.max(180, startTimeoutSeconds))) {
                    phase.set("ready");
                    lastError.set("");
                    return true;
                }
                phase.set("failed");
                lastError.set(tailLog());
                throw new IllegalStateException("本地模型启动失败。日志：\n" + lastError.get());
            } finally {
                starting.set(false);
            }
        }
    }

    /**
     * 只下载模型权重，**不启动运行时**。
     *
     * 给界面「下载模型」按钮和离线预取用：装完机器先下权重，不必为了下 8.9GB
     * 顺手把 llama-server 拉起来（那样会占显存）。已经有权重就直接返回。
     */
    public Status download() {
        if (resolvesModel() != null) {
            return status();
        }
        downloadModelIfAllowed();
        return status();
    }

    /** 手动启动（前端"立即加载模型"按钮用） */
    public Status start() {        try {
            ensureRunning();
        } catch (Exception e) {
            log.warn("手动启动本地模型失败: {}", e.getMessage());
        }
        return status();
    }

    /** 停止本地运行时并释放显存 */
    public void stop() {
        killProcess();
        phase.set("idle");
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 应用根目录候选列表。
     *
     * 为什么要给多个：这个程序有好几种启动方式，工作目录各不相同
     *   - dist\启动LionBox.bat      → 工作目录是 dist，运行时和模型就在旁边
     *   - lion-code\启动.bat        → 工作目录是 lion-code，运行时其实在 dist\ 下
     *   - 打包安装后由 launcher.ps1 起 → 工作目录是安装目录
     * 只认一个目录的话，换个方式启动就会报"找不到运行时"，明明文件就在隔壁。
     */
    private java.util.List<Path> appDirs() {
        java.util.LinkedHashSet<Path> dirs = new java.util.LinkedHashSet<>();
        String home = System.getProperty("lionbox.home");
        if (home != null && !home.isBlank()) {
            dirs.add(Path.of(home));
        }
        String cwd = System.getProperty("user.dir");
        if (cwd != null && !cwd.isBlank()) {
            Path base = Path.of(cwd);
            dirs.add(base);
            dirs.add(base.resolve("dist"));
            dirs.add(base.resolve("output").resolve("LionCode").resolve("app"));
        }
        // jar 所在目录（-jar 启动时和 user.dir 常常一致，但打包后不一定）
        try {
            java.net.URL loc = LocalModelRuntime.class.getProtectionDomain()
                .getCodeSource().getLocation();
            if (loc != null) {
                Path p = Path.of(loc.toURI());
                Path dir = Files.isDirectory(p) ? p : p.getParent();
                if (dir != null) {
                    dirs.add(dir);
                    dirs.add(dir.resolve("dist"));
                }
            }
        } catch (Exception ignored) {
            // 拿不到就只用前面的候选
        }
        return new java.util.ArrayList<>(dirs);
    }

    private Path resolvesExe() {
        if (exePath != null && !exePath.isBlank()) {
            Path p = Path.of(exePath);
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path dir : appDirs()) {
            for (String rel : new String[] {
                "runtime-vulkan/llama-server.exe",
                "runtime-cpu/llama-server.exe",
                "llama-server.exe"}) {
                Path p = dir.resolve(rel);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 模型自动下载（ModelScope）
    // ------------------------------------------------------------------

    /** 权重不在就自动下载；下不了就返回 null，让上层走原有的报错路径。 */
    private Path downloadModelIfAllowed() {
        if (!autoDownload) {
            log.warn("本地没有权重 {}，且已关闭自动下载（lionbox.runtime.auto-download=false）", modelFile);
            return null;
        }
        Path target = downloadTarget();
        if (target == null) {
            log.error("找不到可写目录来存放模型权重");
            return null;
        }
        try {
            phase.set("downloading");
            downloadModel(target);
            phase.set("idle");
            return resolvesModel();
        } catch (Exception e) {
            phase.set("failed");
            lastError.set("自动下载模型失败：" + e.getMessage());
            log.error("自动下载模型失败: {}", e.getMessage());
            return null;
        }
    }

    /** 权重放哪：优先程序目录（可写的话），否则退到 ~/.lioncode/models。 */
    private Path downloadTarget() {
        for (Path dir : appDirs()) {
            try {
                if (Files.isDirectory(dir) && Files.isWritable(dir)) {
                    return dir.resolve(modelFile);
                }
            } catch (Exception ignored) {
                // 下一个候选
            }
        }
        Path home = Path.of(System.getProperty("user.home", "."), ".lioncode", "models");
        try {
            Files.createDirectories(home);
            return home.resolve(modelFile);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从 ModelScope 下载权重。
     *
     * 走 repo 接口（会 302 到 LFS CDN），所以只用 JDK 自带的 HttpClient，
     * **不依赖 Python / modelscope CLI**（用户机器上不该为了下个权重再装一套 Python）：
     *   https://modelscope.cn/api/v1/models/{repo}/repo?Revision={rev}&FilePath={file}
     *
     * 先写 *.part 边下边报进度，下完校验「GGUF 魔数 + 最小体积」再原子改名；
     * 中途断了下次带 Range 续传（.part 留着）。
     */
    private void downloadModel(Path target) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long already = Files.isRegularFile(part) ? Files.size(part) : 0L;
        String url = "https://modelscope.cn/api/v1/models/" + modelRepo + "/repo?Revision="
            + modelRevision + "&FilePath=" + URLEncoder.encode(modelFile, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofHours(6))
            .header("User-Agent", "LionBox/1.1 (model auto-download)");
        if (already > 0) {
            rb.header("Range", "bytes=" + already + "-");
        }
        HttpResponse<java.io.InputStream> resp =
            client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code != 200 && code != 206) {
            throw new IOException("HTTP " + code + "（检查仓库 " + modelRepo + " 与文件名 " + modelFile + "）");
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        boolean append = code == 206 && already > 0;
        if (append && total > 0) {
            total += already;   // 206 只给剩余长度，换算成总长度
        }
        downloadTotal = total;
        downloadBytes = already;
        log.info("开始下载模型 {}（仓库 {}，已下 {} MB{}）", modelFile, modelRepo,
            already / 1024 / 1024,
            total > 0 ? "，共约 " + (total / 1024 / 1024) + " MB" : "");

        long lastLog = 0;
        long t0 = System.currentTimeMillis();
        java.nio.file.OpenOption[] opts = append
            ? new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.WRITE}
            : new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE};
        try (java.io.InputStream in = resp.body();
             java.io.OutputStream out = Files.newOutputStream(part, opts)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                downloadBytes += n;
                if (downloadBytes - lastLog >= 256L * 1024 * 1024) {
                    lastLog = downloadBytes;
                    double sec = Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
                    log.info("  已下载 {} / {} MB（{} MB/s）", downloadBytes / 1024 / 1024,
                        total > 0 ? String.valueOf(total / 1024 / 1024) : "?",
                        String.format("%.1f", (downloadBytes - already) / 1024.0 / 1024.0 / sec));
                }
            }
        }

        long size = Files.size(part);
        byte[] magic = new byte[4];
        try (java.io.InputStream in = Files.newInputStream(part)) {
            if (in.read(magic) != 4) {
                throw new IOException("下载到的文件太短（" + size + " 字节）");
            }
        }
        if (!"GGUF".equals(new String(magic, StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(part);
            throw new IOException("下载到的不是 GGUF（文件头是 \""
                + new String(magic, StandardCharsets.US_ASCII) + "\"，可能是仓库里没有该文件），已丢弃");
        }
        if (size < minModelBytes) {
            throw new IOException("文件只有 " + size + " 字节，远小于预期；保留 .part 以便续传");
        }
        try {
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        downloadTotal = size;
        downloadBytes = size;
        log.info("模型下载完成：{}（{} MB）", target, size / 1024 / 1024);
    }

    private Path resolvesModel() {
        if (modelPath != null && !modelPath.isBlank()) {
            Path p = Path.of(modelPath);
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path dir : appDirs()) {
            for (Path base : new Path[] {dir, dir.resolve("models")}) {
                Path p = base.resolve(modelFile);
                if (Files.isRegularFile(p)) {
                    return p;
                }
                // 安装包里可能只带了另一种量化（例如只带 Q4），
                // 配置里写的是 Q8 —— 那就别让用户看到「找不到模型」，
                // 直接用目录里现成的 .gguf，并把换了哪个文件写进日志。
                Path any = pickAnyGguf(base);
                if (any != null) {
                    log.warn("未找到配置的模型 {}，自动改用 {}", modelFile, any.getFileName());
                    return any;
                }
            }
        }
        return null;
    }

    /** 目录里任意一个 .gguf，量化等级高的优先（Q8 > Q6 > Q5 > Q4 > 其它）。 */
    private Path pickAnyGguf(Path base) {
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (var stream = Files.list(base)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".gguf"))
                    .sorted(Comparator
                            .comparingInt((Path p) -> quantRank(p.getFileName().toString()))
                            .thenComparing(p -> p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("扫描模型目录失败：{}", e.getMessage());
            return null;
        }
    }

    private static int quantRank(String fileName) {
        String n = fileName.toUpperCase();
        if (n.contains("Q8")) return 0;
        if (n.contains("Q6")) return 1;
        if (n.contains("Q5")) return 2;
        if (n.contains("Q4")) return 3;
        if (n.contains("Q3")) return 4;
        return 5;
    }

    private Path logFile() {
        String tmp = System.getProperty("java.io.tmpdir");
        return Path.of(tmp == null ? "." : tmp).resolve("lionbox-model.log");
    }

    /**
     * 拉起 llama-server 并等它健康。
     *
     * @param ngl 放到显卡上的层数（999=全部；0=纯 CPU）
     */
    private boolean spawn(Path exe, Path model, int ngl, int timeoutSec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("-m");
        cmd.add(model.toString());
        cmd.add("-ngl");
        cmd.add(String.valueOf(ngl));
        cmd.add("-c");
        cmd.add(String.valueOf(ctxSize));

        // ---- KV cache 量化 ----
        // 256K 上下文下 KV cache 是显存大头。实测（32K 对照）：
        //   f16  KV buffer = 1024 MiB
        //   q4_0 KV buffer =  288 MiB   （3.56 倍压缩 = 2.0 / 0.5625）
        // 注意：量化 KV 必须开 flash attention，否则 llama.cpp 会直接拒绝该类型，
        // 所以下面看到量化类型就强制把 -fa 打开。
        boolean quantizedKv = kvCacheType != null && !kvCacheType.isBlank()
            && !"f16".equalsIgnoreCase(kvCacheType) && !"bf16".equalsIgnoreCase(kvCacheType);
        if (quantizedKv) {
            cmd.add("-ctk");
            cmd.add(kvCacheType);
            cmd.add("-ctv");
            cmd.add(kvCacheType);
        }
        if (quantizedKv || flashAttn) {
            cmd.add("-fa");
            cmd.add("on");
        }

        // 每个 slot 独占整个上下文：默认多 slot 会把 -c 平分，256K 就只剩几万了
        cmd.add("-np");
        cmd.add(String.valueOf(parallelSlots));

        // ---- 采样默认值 ----
        // 别省这一步：llama-server 出厂默认是 temp 0.80 + repeat-penalty 1.00（=关闭），
        // 实测这个 9B 微调模型在这种配置下会**跑飞** —— 一路重复输出工具调用分片，
        // 生成 3000+ token 不停，请求直接撞上读超时。
        // 降低温度 + 打开重复惩罚后即稳定。
        if (temperature >= 0) {
            cmd.add("--temp");
            cmd.add(String.valueOf(temperature));
        }
        if (topP > 0) {
            cmd.add("--top-p");
            cmd.add(String.valueOf(topP));
        }
        if (repeatPenalty > 0) {
            cmd.add("--repeat-penalty");
            cmd.add(String.valueOf(repeatPenalty));
            cmd.add("--repeat-last-n");
            cmd.add(String.valueOf(repeatLastN));
        }
        // 单次生成上限：默认 -1 会把整个上下文写满（256K！），必须封顶
        if (maxPredict > 0) {
            cmd.add("-n");
            cmd.add(String.valueOf(maxPredict));
        }

        cmd.add("--host");
        cmd.add(host);
        cmd.add("--port");
        cmd.add(String.valueOf(port));

        phase.set(ngl > 0 ? "starting-gpu" : "starting-cpu");
        log.info("正在拉起本地模型运行时：{}（-ngl {}，上下文 {}，KV {}，slot {}，temp {}，repeat-penalty {}，最多 {} token）",
            exe, ngl, ctxSize, quantizedKv ? kvCacheType : "f16", parallelSlots,
            temperature, repeatPenalty, maxPredict);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 工作目录必须是 exe 所在目录：llama-server 依赖同目录的
            // ggml_llamacpp.dll / lmstudiocore.dll / vulkan-1.dll
            pb.directory(exe.getParent().toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(
                logFile().resolveSibling("lionbox-model.err").toFile()));
            process = pb.start();
        } catch (IOException e) {
            lastError.set("启动进程失败: " + e.getMessage());
            log.error("启动本地模型进程失败", e);
            return false;
        }

        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                lastError.set("进程退出，退出码 " + process.exitValue());
                log.error("本地模型进程启动后立即退出，退出码 {}。日志：{}",
                    process.exitValue(), logFile());
                return false;
            }
            if (healthy()) {
                log.info("本地模型已就绪：http://{}:{}/v1（模型 {}）", host, port, modelName);
                return true;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        lastError.set("等待 " + timeoutSec + " 秒仍未就绪");
        log.error("等待本地模型就绪超时（{}秒）", timeoutSec);
        return false;
    }

    /** 探测本地运行时是否已经能服务（/health 优先，根路径在 llama.cpp 上会 404） */
    private boolean healthy() {
        for (String path : new String[] {"/health", "/v1/models", "/"}) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(
                    "http://" + host + ":" + port + path).toURL().openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(2500);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    return true;
                }
            } catch (Exception ignored) {
                // 没起来属于正常情况，继续探测
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return false;
    }

    private void killProcess() {
        Process p = process;
        process = null;
        if (p == null) {
            return;
        }
        long pid = p.pid();
        try {
            // Windows 上 destroy() 只杀直接子进程，不会杀它的子进程树。
            // 实测：如果运行时是通过 .cmd/.bat 包装启动的，destroy() 之后
            // 真正干活的进程还活着、8788 端口还占着、显存也不释放 ——
            // "卸载模型释放显存"这个功能会静默失效。
            // 所以先把后代进程一起收掉，再收自己，最后用 taskkill /T 兜底。
            p.descendants().forEach(h -> {
                try {
                    h.destroy();
                } catch (Exception ignored) {
                    // 单个后代收不掉不影响后面的兜底
                }
            });
            if (p.isAlive()) {
                p.destroy();
                if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            // 兜底：整棵进程树强制结束（对付包装脚本 + 孙进程）
            if (p.isAlive() || !portFree()) {
                taskkillTree(pid);
            }
            log.info("本地模型运行时已停止（PID {}）", pid);
        } catch (Exception e) {
            log.warn("停止本地模型进程时出错: {}", e.getMessage());
            taskkillTree(pid);
        }
    }

    /** taskkill /T /F：Windows 上唯一能可靠杀掉整棵进程树的办法 */
    private void taskkillTree(long pid) {
        try {
            Process k = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                .redirectErrorStream(true)
                .start();
            k.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            log.info("已用 taskkill /T /F 结束进程树 PID {}", pid);
        } catch (Exception e) {
            log.warn("taskkill 失败: {}", e.getMessage());
        }
    }

    /** 端口是否已经没人监听了（用来确认进程真的死透） */
    private boolean portFree() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 800);
            return false;   // 还能连上 = 还有人在服务
        } catch (Exception e) {
            return true;
        }
    }

    private String tailLog() {
        try {
            Path err = logFile().resolveSibling("lionbox-model.err");
            Path target = Files.exists(err) ? err : logFile();
            if (!Files.exists(target)) {
                return "(没有日志)";
            }
            List<String> lines = Files.readAllLines(target);
            int from = Math.max(0, lines.size() - 15);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "(读日志失败: " + e.getMessage() + ")";
        }
    }

    @PreDestroy
    public void onShutdown() {
        stop();
    }
}
