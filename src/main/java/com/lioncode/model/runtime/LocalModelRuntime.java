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
 * 本地模型运行时（llama.cpp）的惰性加载与进程管理。
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

    /** 设置页存下来的 llama 参数（键名见下面的 effective* 方法） */
    private final com.lioncode.model.config.AppConfigStore configStore;

    public LocalModelRuntime(com.lioncode.model.config.AppConfigStore configStore) {
        this.configStore = configStore;
    }

    @Value("${lionbox.runtime.host:127.0.0.1}")
    private String host;

    @Value("${lionbox.runtime.port:8788}")
    private int port;

    @Value("${lionbox.runtime.model-file:lion-merged-Q8_0.gguf}")
    private String modelFile;

    @Value("${lionbox.runtime.model-name:lion-models1}")
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

    // ---- 下面这些是"设置页能改"的参数（原来没暴露的） ----
    /** CPU 线程数（0 = 让 llama.cpp 自己决定） */
    @Value("${lionbox.runtime.threads:0}")
    private int threads;

    /** 批处理大小（-b）/ 微批（-ub） */
    @Value("${lionbox.runtime.batch-size:2048}")
    private int batchSize;

    @Value("${lionbox.runtime.ubatch-size:512}")
    private int ubatchSize;

    @Value("${lionbox.runtime.top-k:40}")
    private int topK;

    @Value("${lionbox.runtime.min-p:0.05}")
    private double minP;

    @Value("${lionbox.runtime.seed:-1}")
    private long seed;

    // ================= 设置页读写用的"有效值" =================
    // 规则：app-config.json 的 llama 段优先，没有就用 application.yml 的默认值。
    // 键名就是命令行参数的语义名（modelFile / ctxSize / ngl / kvCacheTypeK …

    /** 读一个字符串参数（设置页存的优先） */
    private String cfgStr(String key, String fallback) {
        Object v = configStore == null ? null : configStore.llamaConfig().get(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    /** 读一个整数参数 */
    private int cfgInt(String key, int fallback) {
        try {
            String s = cfgStr(key, null);
            return s == null ? fallback : Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 读一个小数参数 */
    private double cfgDouble(String key, double fallback) {
        try {
            String s = cfgStr(key, null);
            return s == null ? fallback : Double.parseDouble(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 读一个开关参数（true/false/1/0/on/off 都认） */
    private boolean cfgBool(String key, boolean fallback) {
        String s = cfgStr(key, null);
        if (s == null) {
            return fallback;
        }
        s = s.toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes");
    }

    /**
     * 当前生效的模型文件名（安装时选的 / 设置里改的优先，否则用配置默认的）。
     * 这是"安装时选模型"能生效的关键：只需要改这一个值。
     */
    public String effectiveModelFile() {
        return cfgStr("modelFile", modelFile);
    }

    /** 当前生效的全部参数（给设置页看，也给日志看） */
    public java.util.Map<String, Object> effectiveConfig() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("modelFile", effectiveModelFile());
        m.put("modelName", modelName);
        m.put("host", cfgStr("host", host));
        m.put("port", cfgInt("port", port));
        m.put("ctxSize", cfgInt("ctxSize", ctxSize));
        m.put("ngl", cfgInt("ngl", 999));
        m.put("kvCacheTypeK", cfgStr("kvCacheTypeK", cfgStr("kvCacheType", kvCacheType)));
        m.put("kvCacheTypeV", cfgStr("kvCacheTypeV", cfgStr("kvCacheType", kvCacheType)));
        m.put("flashAttn", cfgBool("flashAttn", flashAttn));
        m.put("parallelSlots", cfgInt("parallelSlots", parallelSlots));
        m.put("threads", cfgInt("threads", threads));
        m.put("batchSize", cfgInt("batchSize", batchSize));
        m.put("ubatchSize", cfgInt("ubatchSize", ubatchSize));
        m.put("temperature", cfgDouble("temperature", temperature));
        m.put("topP", cfgDouble("topP", topP));
        m.put("topK", cfgInt("topK", topK));
        m.put("minP", cfgDouble("minP", minP));
        m.put("repeatPenalty", cfgDouble("repeatPenalty", repeatPenalty));
        m.put("repeatLastN", cfgInt("repeatLastN", repeatLastN));
        m.put("seed", cfgInt("seed", (int) seed));
        m.put("maxPredict", cfgInt("maxPredict", maxPredict));
        m.put("extraArgs", cfgStr("extraArgs", ""));
        return m;
    }

    // ---- 模型自动下载（首次运行的"装完即用"）----
    // 安装包不再内置 8.9GB 权重，改为首次用到时从 ModelScope 拉取。
    // 下载地址走 ModelScope 的 repo 接口（会 302 到 CDN），不需要装 Python / modelscope CLI。
    @Value("${lionbox.runtime.model-repo:lionnezha/lion-models}")
    private String modelRepo;

    // 下载源前缀。默认就是 ModelScope；做成可配置主要是为了两件事：
    //   1) 自测时指向本地假服务器，验证"确实去下了用户选的那个量化"；
    //   2) 哪天要换镜像站（下载慢的时候），改配置即可，不必重编译。
    @Value("${lionbox.runtime.model-base-url:https://modelscope.cn}")
    private String modelBaseUrl;

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

    /** 当前**实际在用**的模型文件名（可能因为兜底和配置的不一样） */
    public String modelFileInUse() {
        return modelInUse.get();
    }

    /**
     * 配置的模型还没就位时，给界面一句能看懂的话；一切正常则返回空串。
     * 例：「你选的是 lion-merged-IQ4_XS.gguf，但它还没下载，现在临时用 lion-merged-Q8_0.gguf」。
     */
    public String modelMismatchNotice() {
        String inUse = modelInUse.get();
        String configured = effectiveModelFile();
        if (inUse == null || inUse.isEmpty() || configured == null || inUse.equals(configured)) {
            return "";
        }
        return "你选的是 " + configured + "，但" +
            (isModelDownloaded(configured) ? "" : "它还没下载，") +
            "现在临时在用 " + inUse;
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
            effectiveModelFile(),
            resolvesExe() == null ? "" : resolvesExe().toString(),
            modelInUse.get().isEmpty()
                ? (resolvesModel() == null ? "" : resolvesModel().toString())
                : modelInUse.get(),
            logFile() == null ? "" : logFile().toString(),
            lastError.get(),
            downloadBytes,
            downloadTotal,
            downloadingFile.get(),
            modelDir()
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
        /** 下载进度：已下字节 / 总字节（-1 = 未知），phase=downloading 时前端可以显示进度 */
        long downloadBytes,
        long downloadTotal,
        /** 正在下载哪一份权重（空 = 没在下） */
        String downloadingFile,
        /** 模型权重放哪个目录（界面要把这个路径显示给用户，他可以自己往里塞 GGUF） */
        String modelDir
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
            // 【顺序要紧】先找**配置的那一个**；没有就下载它；实在下不来才退回目录里现成的。
            // 以前这里调的是 resolvesModel()（会兜底返回任意 .gguf），
            // 导致"用户在安装时选了 IQ4_XS"永远走不到下载分支，启动的还是老的 Q8_0。
            Path model = configuredModelPath();
            modelIsFallback.set(false);
            if (exe != null && model == null) {
                model = downloadModelIfAllowed();
            }
            if (model == null) {
                model = resolvesModel();          // 下载失败/关闭了自动下载：退现成的
                if (model != null) {
                    modelIsFallback.set(true);
                }
            }
            if (model != null) {
                modelInUse.set(model.getFileName().toString());
            }
            if (exe == null || model == null) {
                lastError.set("找不到本地模型运行时或模型文件");
                phase.set("missing");
                log.error("本地模型运行时不可用: exe={}, model={}", exe, model);
                throw new IllegalStateException(
                    "本地模型运行时不可用：请确认程序目录下有 runtime-vulkan\\llama-server.exe 与 "
                        + effectiveModelFile() + "；或切换到「自定义 API」模式。"
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
        // 【坑】这里以前判的是 resolvesModel() —— 它会"找不到配置的就拿现成的"，
        // 于是配置的那份明明没下，也会被当成"已经有了"，按钮点了没反应。
        // 只看配置的那一份在不在，缺了才去下。
        if (configuredModelPath() != null) {
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

    /** 同一时刻只允许一个下载（开机后台下载、界面下载按钮、第一条消息可能同时来抢）。 */
    private final java.util.concurrent.atomic.AtomicBoolean downloadingNow =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 正在下载哪一份权重（空 = 没在下）。界面要能说清"在下的这一份"，而不只是个百分比。 */
    private final java.util.concurrent.atomic.AtomicReference<String> downloadingFile =
        new java.util.concurrent.atomic.AtomicReference<>("");

    /**
     * 开机后把"用户选的那份权重"在后台补齐。
     *
     * <p>为什么不在第一条消息时才下：用户装的时候选了 IQ4_XS（4.87 GB），
     * 那份不可能塞进安装包，只能下。要是等他发第一条消息时才下，那条消息就得干等十几分钟，
     * 看起来就是"卡死了"。放这儿下：不加载模型、不占显存，进度显示在顶部横幅上；
     * 这期间聊天照样能用（会先用目录里现成的那份，并如实说明用的是哪份）。
     */
    @org.springframework.context.event.EventListener(
        org.springframework.context.event.ContextRefreshedEvent.class)
    public void fetchConfiguredModelOnStartup() {
        if (!autoDownload) {
            return;
        }
        if (configStore != null && !configStore.isLocalMode()) {
            return;                                   // 用户在用自定义 API，别偷偷占 5 GB 硬盘
        }
        if (resolvesExe() == null) {
            return;                                   // 没装本地运行时，本地模型用不上
        }
        if (configuredModelPath() != null) {
            return;                                   // 已经有了，不折腾
        }
        Thread t = new Thread(() -> {
            log.info("你选的模型 {} 本机还没有，后台开始下载（不占显存，进度见界面）",
                effectiveModelFile());
            downloadModelIfAllowed();
        }, "lionbox-model-fetch");
        t.setDaemon(true);
        t.start();
    }

    /** 配置里那一份不在就自动下载；下不了就返回 null，让上层走原有的报错路径。 */
    private Path downloadModelIfAllowed() {
        if (!autoDownload) {
            log.warn("本地没有权重 {}，且已关闭自动下载（lionbox.runtime.auto-download=false）",
                effectiveModelFile());
            return null;
        }
        return downloadFileIfAllowed(effectiveModelFile());
    }

    /**
     * 后台下载**指定的一份**权重（不切换当前模型）。
     *
     * <p>给界面每一行的「下载」按钮用：用户可以先把几份都下好，再挑一份用。
     * 同一时刻只允许一个下载（另一份在下载时点别的会被告知"正在下载"）。
     *
     * @return started/downloaded/busy/error 之一，界面据此提示
     */
    public java.util.Map<String, Object> startDownload(String file) {
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        if (file == null || file.isBlank()) {
            r.put("started", false);
            r.put("error", "缺少文件名");
            return r;
        }
        if (!isKnownModel(file)) {
            // 只从官方仓库下清单里那几份；用户自己塞的 GGUF 本来就在本地，不用下
            r.put("started", false);
            r.put("error", "只能下载清单里的量化版本：" + knownModelNames());
            return r;
        }
        if (isModelDownloaded(file)) {
            r.put("started", false);
            r.put("downloaded", true);
            return r;
        }
        if (downloadingNow.get()) {
            r.put("started", false);
            r.put("busy", true);
            r.put("error", "已经有一个下载在进行中（" + downloadingFile.get() + "），等它下完");
            return r;
        }
        Thread t = new Thread(() -> downloadFileIfAllowed(file), "lionbox-model-dl");
        t.setDaemon(true);
        t.start();
        r.put("started", true);
        return r;
    }

    private Path downloadFileIfAllowed(String file) {
        if (!downloadingNow.compareAndSet(false, true)) {
            log.info("已有一个下载在进行中，这次不重复下");
            return null;
        }
        try {
            Path target = downloadTarget(file);
            if (target == null) {
                log.error("找不到可写目录来存放模型权重");
                return null;
            }
            try {
                downloadingFile.set(file);
                downloadBytes = 0;
                downloadTotal = -1;
                phase.set("downloading");
                downloadModel(target, file);
                phase.set("idle");
                log.info("权重已就绪：{}，可到「设置 → 模型版本」里点「使用」", file);
                return findModelFile(file);
            } catch (Exception e) {
                phase.set("failed");
                lastError.set("下载模型失败：" + e.getMessage());
                log.error("下载模型失败: {}", e.getMessage());
                return null;
            } finally {
                downloadingFile.set("");
            }
        } finally {
            downloadingNow.set(false);
        }
    }

    // ------------------------------------------------------------------
    // 模型目录：告诉用户文件放哪、扫出他自己塞进来的 GGUF
    // ------------------------------------------------------------------

    /** 会被搜模型文件的目录（含各自的 models 子目录）。 */
    public java.util.List<String> modelDirs() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Path d : scanDirs()) {
            if (!out.contains(d.toString())) {
                out.add(d.toString());
            }
        }
        return out;
    }

    /** 模型默认放哪（下载就落这个目录，界面把它显示给用户） */
    public String modelDir() {
        Path t = downloadTarget(effectiveModelFile());
        if (t != null && t.getParent() != null) {
            return t.getParent().toString();
        }
        Path home = Path.of(System.getProperty("user.home", "."), ".lioncode", "models");
        return home.toString();
    }

    private java.util.List<Path> scanDirs() {
        java.util.List<Path> dirs = new java.util.ArrayList<>(appDirs());
        dirs.add(Path.of(System.getProperty("user.home", "."), ".lioncode", "models"));
        return dirs;
    }

    /**
     * 本地现成的权重（含**用户自己塞进来的**那些 GGUF）。
     *
     * <p>不要求 ≥ minModelBytes：用户可能就放个小模型来试；只有太小的
     * （&lt; 1 MB）才当垃圾忽略掉。同名文件按目录顺序取先找到的那个。
     */
    public java.util.List<LocalModel> scanLocalModels() {
        java.util.LinkedHashMap<String, LocalModel> found = new java.util.LinkedHashMap<>();
        for (Path dir : scanDirs()) {
            for (Path base : new Path[] {dir, dir.resolve("models")}) {
                if (!Files.isDirectory(base)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> st = Files.list(base)) {
                    for (Path p : st.toList()) {
                        String name = p.getFileName().toString();
                        if (!name.toLowerCase().endsWith(".gguf")) {
                            continue;                       // *.gguf.part 之类不算
                        }
                        if (!Files.isRegularFile(p)) {
                            continue;
                        }
                        long size = Files.size(p);
                        if (size < 1024L * 1024) {
                            continue;                       // 1 MB 以下当垃圾
                        }
                        if (found.containsKey(name)) {
                            continue;
                        }
                        found.put(name, new LocalModel(name, p.toString(),
                            Math.round(size / 1073741824.0 * 1000) / 1000.0, isKnownModel(name), size));
                    }
                } catch (Exception ignored) {
                    // 目录读不了就跳过
                }
            }
        }
        java.util.List<LocalModel> list = new java.util.ArrayList<>(found.values());
        list.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));   // 大的在前
        return list;
    }

    /** 本地现成的一份权重（给"界面点使用"判断能不能用） */
    public boolean hasModelFile(String file) {
        return file != null && findModelFile(file) != null;
    }

    private Path findModelFile(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        for (Path dir : scanDirs()) {
            for (Path base : new Path[] {dir, dir.resolve("models")}) {
                Path p = base.resolve(file);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private boolean isKnownModel(String file) {
        for (ModelChoice c : AVAILABLE_MODELS) {
            if (c.file().equals(file)) {
                return true;
            }
        }
        return false;
    }

    private String knownModelNames() {
        StringBuilder sb = new StringBuilder();
        for (ModelChoice c : AVAILABLE_MODELS) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(c.file());
        }
        return sb.toString();
    }

    /** 本地现成的一份权重（把用户自己塞的 GGUF 也算进来） */
    public record LocalModel(String file, String path, double sizeGb, boolean known, long bytes) {}

    /** 权重放哪：优先程序目录（可写的话），否则退到 ~/.lioncode/models。 */
    /**
     * 可选的模型版本（ModelScope 仓库 lionnezha/lion-models 里实际存在的三份）。
     *
     * <p>安装包的"选择模型版本"页和设置页的"模型"一节都用这份清单；
     * 换模型只要把 {@code llama.modelFile} 改成这里的 file 即可。
     */
    public record ModelChoice(String file, String label, double sizeGb, String note) {}

    /** 可选模型清单（体积是仓库里报的实际大小） */
    public static final java.util.List<ModelChoice> AVAILABLE_MODELS = java.util.List.of(
        new ModelChoice("lion-merged-Q8_0.gguf", "Q8_0（最高质量·默认）", 8.87,
            "原版精度，回答质量最好；下载 8.87 GB，解码约 11 token/s"),
        new ModelChoice("lion-merged-Q4_K_M.gguf", "Q4_K_M（平衡·推荐）", 5.24,
            "体积小 40%，解码约 1.7 倍快；质量略降"),
        new ModelChoice("lion-merged-IQ4_XS.gguf", "IQ4_XS（最小最快）", 4.87,
            "体积最小、速度最快；质量下降最明显，适合只看响应速度的场合")
    );

    /** 这个模型文件本地是否已经有了（设置页/模型清单里用来标"已下载"）。 */
    public boolean isModelDownloaded(String file) {
        if (file == null || file.isBlank()) {
            return false;
        }
        try {
            long min = minModelBytes;
            for (Path dir : appDirs()) {
                Path p = dir.resolve(file);
                if (Files.isRegularFile(p) && Files.size(p) >= min) {
                    return true;
                }
            }
            Path home = Path.of(System.getProperty("user.home", "."), ".lioncode", "models", file);
            return Files.isRegularFile(home) && Files.size(home) >= min;
        } catch (Exception e) {
            return false;
        }
    }

    private Path downloadTarget(String file) {
        String name = (file == null || file.isBlank()) ? effectiveModelFile() : file;
        for (Path dir : appDirs()) {
            try {
                if (Files.isDirectory(dir) && Files.isWritable(dir)) {
                    return dir.resolve(name);
                }
            } catch (Exception ignored) {
                // 下一个候选
            }
        }
        Path home = Path.of(System.getProperty("user.home", "."), ".lioncode", "models");
        try {
            Files.createDirectories(home);
            return home.resolve(name);
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
    private void downloadModel(Path target, String file) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long already = Files.isRegularFile(part) ? Files.size(part) : 0L;
        String url = modelBaseUrl + "/api/v1/models/" + modelRepo + "/repo?Revision="
            + modelRevision + "&FilePath=" + URLEncoder.encode(file, StandardCharsets.UTF_8);

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
            throw new IOException("HTTP " + code + "（检查仓库 " + modelRepo + " 与文件名 "
                    + effectiveModelFile() + "）");
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        boolean append = code == 206 && already > 0;
        if (append && total > 0) {
            total += already;   // 206 只给剩余长度，换算成总长度
        }
        downloadTotal = total;
        downloadBytes = already;
        log.info("开始下载模型 {}（仓库 {}，已下 {} MB{}）", file, modelRepo,
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

    /** 实际这次启动用的模型文件（兜底时会和配置的不一样，界面要如实显示） */
    private final java.util.concurrent.atomic.AtomicReference<String> modelInUse =
        new java.util.concurrent.atomic.AtomicReference<>("");

    /** 上次为哪一对（配置的 / 实际用的）打过兜底警告，避免状态轮询把日志刷爆 */
    private final java.util.concurrent.atomic.AtomicReference<String> lastFallbackWarn =
        new java.util.concurrent.atomic.AtomicReference<>("");

    /** 兜底警告只打一次（同一对不再重复） */
    private void warnFallbackOnce(String configured, String inUse) {
        String key = configured + " -> " + inUse;
        if (!key.equals(lastFallbackWarn.getAndSet(key))) {
            log.warn("配置的模型 {} 不可用，暂时改用现成的 {}（可在设置里下载配置的那个）",
                configured, inUse);
        }
    }

    /** 这次启动用的是不是兜底的模型（配置的那个没找到） */
    private final java.util.concurrent.atomic.AtomicBoolean modelIsFallback =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 只要**配置里那一个**模型文件；没有就返回 null（让调用方去下载）。
     *
     * <p>为什么不能在这里"随便拿一个现成的"：用户可以在安装时/设置里选量化版本，
     * 一兜底他的选择就白选了 —— 实测"选了 IQ4_XS，启动还是 Q8_0"就是这么来的。
     */
    private Path configuredModelPath() {
        if (modelPath != null && !modelPath.isBlank()) {
            Path p = Path.of(modelPath);
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path dir : appDirs()) {
            for (Path base : new Path[] {dir, dir.resolve("models")}) {
                Path p = base.resolve(effectiveModelFile());
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private Path resolvesModel() {
        if (modelPath != null && !modelPath.isBlank()) {
            Path p = Path.of(modelPath);
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path dir : appDirs()) {
            for (Path base : new Path[] {dir, dir.resolve("models")}) {
                Path p = base.resolve(effectiveModelFile());
                if (Files.isRegularFile(p)) {
                    return p;
                }
                // 安装包里可能只带了另一种量化（例如只带 Q4），
                // 配置里写的是 Q8 —— 那就别让用户看到「找不到模型」，
                // 直接用目录里现成的 .gguf，并把换了哪个文件写进日志。
                Path any = pickAnyGguf(base);
                if (any != null) {
                    // 这条警告以前每次 status() 都打（界面每 1.5 秒轮询一次状态 →
                    // 日志刷刷刷）。改成同一对（配置的 / 实际用的）只打一次，换人了再打。
                    warnFallbackOnce(effectiveModelFile(), any.getFileName().toString());
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
     * 把 "‑‑no-mmap --flash-attn on" 这样的自由参数串拆成一个个参数。
     * 支持用引号把带空格的值括起来（例如 --chat-template-file "C:\\my dir\\t.jinja"）。
     */
    static java.util.List<String> splitArgs(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (char c : raw.toCharArray()) {
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
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
        cmd.add(String.valueOf(cfgInt("ctxSize", ctxSize)));

        // ---- KV cache 量化 ----
        // 256K 上下文下 KV cache 是显存大头。实测（32K 对照）：
        //   f16  KV buffer = 1024 MiB
        //   q4_0 KV buffer =  288 MiB   （3.56 倍压缩 = 2.0 / 0.5625）
        // 注意：量化 KV 必须开 flash attention，否则 llama.cpp 会直接拒绝该类型，
        // 所以下面看到量化类型就强制把 -fa 打开。
        String kvK = cfgStr("kvCacheTypeK", cfgStr("kvCacheType", kvCacheType));
        String kvV = cfgStr("kvCacheTypeV", cfgStr("kvCacheType", kvCacheType));
        boolean quantizedKv = kvK != null && !kvK.isBlank()
            && !"f16".equalsIgnoreCase(kvK) && !"bf16".equalsIgnoreCase(kvK);
        if (quantizedKv) {
            cmd.add("-ctk");
            cmd.add(kvK);
            cmd.add("-ctv");
            cmd.add(kvV);
        }
        if (quantizedKv || cfgBool("flashAttn", flashAttn)) {
            cmd.add("-fa");
            cmd.add("on");
        }

        // 每个 slot 独占整个上下文：默认多 slot 会把 -c 平分，256K 就只剩几万了
        cmd.add("-np");
        cmd.add(String.valueOf(cfgInt("parallelSlots", parallelSlots)));

        // ---- 线程 / 批处理（原来固定用默认，现在设置页可改）----
        int th = cfgInt("threads", threads);
        if (th > 0) {
            cmd.add("-t");
            cmd.add(String.valueOf(th));
            cmd.add("-tb");
            cmd.add(String.valueOf(th));
        }
        int bs = cfgInt("batchSize", batchSize);
        if (bs > 0) {
            cmd.add("-b");
            cmd.add(String.valueOf(bs));
        }
        int ub = cfgInt("ubatchSize", ubatchSize);
        if (ub > 0) {
            cmd.add("-ub");
            cmd.add(String.valueOf(ub));
        }

        // ---- 采样默认值 ----
        // 别省这一步：llama-server 出厂默认是 temp 0.80 + repeat-penalty 1.00（=关闭），
        // 实测这个 9B 微调模型在这种配置下会**跑飞** —— 一路重复输出工具调用分片，
        // 生成 3000+ token 不停，请求直接撞上读超时。
        // 降低温度 + 打开重复惩罚后即稳定。
        double effTemp = cfgDouble("temperature", temperature);
        if (effTemp >= 0) {
            cmd.add("--temp");
            cmd.add(String.valueOf(effTemp));
        }
        double effTopP = cfgDouble("topP", topP);
        if (effTopP > 0) {
            cmd.add("--top-p");
            cmd.add(String.valueOf(effTopP));
        }
        int effTopK = cfgInt("topK", topK);
        if (effTopK > 0) {
            cmd.add("--top-k");
            cmd.add(String.valueOf(effTopK));
        }
        double effMinP = cfgDouble("minP", minP);
        if (effMinP > 0) {
            cmd.add("--min-p");
            cmd.add(String.valueOf(effMinP));
        }
        double effRepeat = cfgDouble("repeatPenalty", repeatPenalty);
        if (effRepeat > 0) {
            cmd.add("--repeat-penalty");
            cmd.add(String.valueOf(effRepeat));
            cmd.add("--repeat-last-n");
            cmd.add(String.valueOf(cfgInt("repeatLastN", repeatLastN)));
        }
        int effSeed = cfgInt("seed", (int) seed);
        if (effSeed >= 0) {
            cmd.add("--seed");
            cmd.add(String.valueOf(effSeed));
        }
        // 单次生成上限：默认 -1 会把整个上下文写满（256K！），必须封顶
        int effMaxPredict = cfgInt("maxPredict", maxPredict);
        if (effMaxPredict > 0) {
            cmd.add("-n");
            cmd.add(String.valueOf(effMaxPredict));
        }

        // ---- 自由参数：设置页里可以填任何我们没做的开关 ----
        String extra = cfgStr("extraArgs", "");
        if (!extra.isBlank()) {
            cmd.addAll(splitArgs(extra));
        }

        cmd.add("--host");
        cmd.add(cfgStr("host", host));
        cmd.add("--port");
        cmd.add(String.valueOf(cfgInt("port", port)));

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
