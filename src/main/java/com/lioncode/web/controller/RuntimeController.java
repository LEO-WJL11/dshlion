package com.lioncode.web.controller;

import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.config.AppConfigStore;
import com.lioncode.model.config.ModelProviderConfig;
import com.lioncode.model.runtime.LocalModelRuntime;
import com.lioncode.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型运行方式控制器（本地模型 / 自定义 API）
 *
 * 两条使用路径：
 *
 * 【本地模型】
 *   用随盒子交付的 GGUF + llama.cpp。
 *   应用启动时**不加载**模型，用户发出第一条消息时才由 LocalModelRuntime 惰性拉起。
 *   这里额外提供手动"立即加载/卸载"，方便用户想提前热好、或想腾出显存。
 *
 * 【自定义 API】
 *   用户自己填 OpenAI 兼容的 baseUrl / apiKey / model。
 *   切到这个模式后本地模型永远不会被加载。
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {

    private static final Logger log = LoggerFactory.getLogger(RuntimeController.class);

    private final AppConfigStore configStore;
    private final AdapterManager adapterManager;
    private final LocalModelRuntime localRuntime;
    private final com.lioncode.model.runtime.PrewarmService prewarmService;

    /**
     * 仅用于「提示词体检」接口（GET /api/runtime/prompt-preview）。
     * 用字段注入 + required=false：避免为了一个调试接口去改构造器、
     * 也避免和 AgentLoop 形成构造器环。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lioncode.core.agent.AgentLoop agentLoop;

    public RuntimeController(AppConfigStore configStore, AdapterManager adapterManager,
                             LocalModelRuntime localRuntime,
                             com.lioncode.model.runtime.PrewarmService prewarmService) {
        this.configStore = configStore;
        this.adapterManager = adapterManager;
        this.localRuntime = localRuntime;
        this.prewarmService = prewarmService;
    }

    /**
     * 当前运行方式与端点信息（前端初始化时调用）
     *
     * customApi 是独立的"用户填过的 API 配置"存档：
     * 切到本地模式时当前生效的端点会变成内置运行时，但这份存档必须留着，
     * 否则用户切回来就得把地址和 Key 重新敲一遍（这就是"点了本地模型就点不回云端"的原因）。
     */
    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> getMode() {
        Map<String, Object> out = new HashMap<>();
        out.put("mode", configStore.get("providerMode", AppConfigStore.MODE_LOCAL));
        out.put("baseUrl", configStore.get("baseUrl", ""));
        out.put("model", configStore.get("model", ""));
        out.put("apiKey", configStore.get("apiKey", ""));
        out.put("customApi", configStore.getMap("customApi"));
        out.put("localBaseUrl", ModelProviderConfig.localBaseUrl());
        out.put("localModel", ModelProviderConfig.LOCAL_MODEL_NAME);
        out.put("lazyLoad", localRuntime.isLazyLoad());
        out.put("localStatus", localRuntime.status());
        out.put("toolCallMode", configStore.toolCallMode());
        return ApiResponse.ok(out);
    }

    /**
     * 设置工具调用方式（auto / native / text）
     *
     * 单独开一个接口而不是塞进 /mode：切运行方式会顺带改写端点与密钥，
     * 用户只是换个工具调用格式时不该碰到那些东西。
     */
    @PostMapping("/tool-call-mode")
    public ApiResponse<Map<String, Object>> setToolCallMode(@RequestBody ToolCallModeRequest request) {
        String mode = AppConfigStore.normalizeToolCallMode(request.mode());
        if (mode == null) {
            return ApiResponse.error("无效的工具调用方式: " + request.mode()
                + "（只支持 auto / native / text）");
        }
        configStore.set("toolCallMode", mode);
        log.info("工具调用方式已切换: {}", mode);
        Map<String, Object> out = new HashMap<>();
        out.put("toolCallMode", mode);
        return ApiResponse.ok("已保存", out);
    }

    /**
     * 切换运行方式
     *
     * 切到 custom：把用户填的 baseUrl / apiKey / model 存进 customApi 存档，并立即生效。
     * 切到 local ：当前端点复位到内置运行时、密钥清空；
     *              **customApi 存档原样保留**，下次切回来直接带出来；
     *              **不立即加载模型**，等第一条消息再加载。
     */
    @PostMapping("/mode")
    public ApiResponse<Map<String, Object>> setMode(@RequestBody ModeRequest request) {
        String mode = request.mode() == null ? "" : request.mode().trim().toLowerCase();
        if (!AppConfigStore.MODE_LOCAL.equals(mode) && !AppConfigStore.MODE_CUSTOM.equals(mode)) {
            return ApiResponse.error("无效的运行方式: " + request.mode() + "（只支持 local / custom）");
        }

        Map<String, Object> updates = new HashMap<>();

        if (AppConfigStore.MODE_CUSTOM.equals(mode)) {
            // 刻意不预设任何厂商：URL / Key / 模型名全部由用户填，这里只做格式校验
            String baseUrl = request.baseUrl() == null ? "" : request.baseUrl().trim();
            String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
            String model = request.model() == null ? "" : request.model().trim();

            // 前端如果把表单留空了（比如只在存档里有值），就回落到存档，
            // 避免"明明填过却说要重填"
            Map<String, Object> saved = configStore.getMap("customApi");
            if (baseUrl.isBlank()) {
                baseUrl = str(saved.get("baseUrl"));
            }
            if (model.isBlank()) {
                model = str(saved.get("model"));
            }
            if (apiKey.isBlank()) {
                apiKey = str(saved.get("apiKey"));
            }

            if (baseUrl.isBlank()) {
                return ApiResponse.error("请填写接口地址（Base URL）");
            }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                return ApiResponse.error("接口地址需要以 http:// 或 https:// 开头");
            }
            if (model.isBlank()) {
                return ApiResponse.error("请填写模型名称（填服务商给出的模型 ID）");
            }
            // 用户填什么就存什么，绝不覆盖
            updates.put("providerMode", AppConfigStore.MODE_CUSTOM);
            updates.put("provider", "lionbox-custom");
            updates.put("baseUrl", baseUrl);
            updates.put("model", model);
            updates.put("apiKey", apiKey);
        } else {
            updates.put("providerMode", AppConfigStore.MODE_LOCAL);
            updates.put("provider", "lionbox-local");
            updates.put("baseUrl", ModelProviderConfig.localBaseUrl());
            updates.put("model", ModelProviderConfig.LOCAL_MODEL_NAME);
            updates.put("apiKey", "");   // 当前生效的密钥清掉，但存档不动
        }

        configStore.update(updates);

        // 切到自定义模式时，把这份配置写进存档（切到本地时一个字都不动）
        if (AppConfigStore.MODE_CUSTOM.equals(mode)) {
            Map<String, Object> archive = new HashMap<>();
            archive.put("baseUrl", updates.get("baseUrl"));
            archive.put("apiKey", updates.get("apiKey"));
            archive.put("model", updates.get("model"));
            configStore.set("customApi", archive);
        }

        // 同步给适配器：不然界面上切了、实际请求还用旧端点
        Map<String, Object> adapterCfg = new HashMap<>();
        adapterCfg.put("baseUrl", updates.get("baseUrl"));
        adapterCfg.put("apiKey", updates.get("apiKey"));
        adapterManager.getAdapter(ModelAdapter.AdapterType.OPENAI_COMPATIBLE)
            .ifPresent(a -> a.updateConfig(adapterCfg));

        // 同步适配器级配置块（重启后由 AppConfigStore 读取）
        Map<String, Object> block = new HashMap<>(configStore.getMap("openai"));
        block.put("baseUrl", updates.get("baseUrl"));
        block.put("model", updates.get("model"));
        block.put("apiKey", updates.get("apiKey"));
        configStore.set("openai", block);

        log.info("运行方式已切换: mode={}, baseUrl={}, model={}（自定义 API 配置已存档保留）",
            mode, updates.get("baseUrl"), updates.get("model"));

        return ApiResponse.ok("已切换", getMode().data());
    }

    private static String str(Object o) {
        return o instanceof String s ? s : (o == null ? "" : String.valueOf(o));
    }

    /**
     * 本地运行时状态
     */
    @GetMapping("/local")
    public ApiResponse<LocalModelRuntime.Status> localStatus() {
        return ApiResponse.ok(localRuntime.status());
    }

    /**
     * 读当前生效的 llama.cpp 启动参数（设置页用）。
     */
    @GetMapping("/local/config")
    public ApiResponse<Map<String, Object>> localConfig() {
        Map<String, Object> data = new java.util.LinkedHashMap<>(localRuntime.effectiveConfig());
        data.put("running", localRuntime.isRunning());
        data.put("status", localRuntime.status());
        data.put("stored", configStore.llamaConfig());
        return ApiResponse.ok(data);
    }

    /**
     * 改 llama.cpp 启动参数。**改完需要重启本地模型才生效**（界面会提示）。
     * 传什么改什么，没传的保持原样；传空字符串表示删掉这项（回到默认值）。
     */
    @PostMapping("/local/config")
    public ApiResponse<Map<String, Object>> updateLocalConfig(@RequestBody Map<String, Object> updates) {
        log.info("用户修改 llama.cpp 参数: {}", updates == null ? "(空)" : updates.keySet());
        configStore.updateLlamaConfig(updates);
        Map<String, Object> data = new java.util.LinkedHashMap<>(localRuntime.effectiveConfig());
        data.put("running", localRuntime.isRunning());
        data.put("needRestart", localRuntime.isRunning());
        return ApiResponse.ok("参数已保存" + (localRuntime.isRunning() ? "，重启本地模型后生效" : ""), data);
    }

    /**
     * 可下载的模型清单（安装时的选择项也是这几份）。
     */
    @GetMapping("/local/models")
    public ApiResponse<List<Map<String, Object>>> localModels() {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        String current = localRuntime.effectiveModelFile();
        for (LocalModelRuntime.ModelChoice c : LocalModelRuntime.AVAILABLE_MODELS) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("file", c.file());
            m.put("label", c.label());
            m.put("sizeGb", c.sizeGb());
            m.put("note", c.note());
            m.put("current", c.file().equals(current));
            m.put("downloaded", localRuntime.isModelDownloaded(c.file()));
            list.add(m);
        }
        return ApiResponse.ok(list);
    }

    /**
     * 换模型（可选同时重启）。
     *
     * <p>界面上"设置 → 模型"里选一个：没下载的会先下（几百 MB 到 9 GB，看量化），
     * 下完自动重启运行时。安装包里选的那一项也是写到同一个配置键。
     */
    @PostMapping("/local/model")
    public ApiResponse<LocalModelRuntime.Status> switchModel(@RequestBody Map<String, Object> body) {
        String file = body == null ? null : str(body.get("file"));
        if (file == null || file.isBlank()) {
            return ApiResponse.error("缺少 file（要切换到的模型文件名）");
        }
        String matched = null;
        for (LocalModelRuntime.ModelChoice c : LocalModelRuntime.AVAILABLE_MODELS) {
            if (c.file().equals(file)) {
                matched = c.file();
                break;
            }
        }
        if (matched == null) {
            return ApiResponse.error("不认识的模型: " + file + "（可选：" + LocalModelRuntime.AVAILABLE_MODELS.stream()
                .map(LocalModelRuntime.ModelChoice::file).reduce((a, b) -> a + "、" + b).orElse("") + "）");
        }
        log.info("用户切换本地模型: {} → {}", localRuntime.effectiveModelFile(), matched);
        configStore.updateLlamaConfig(Map.of("modelFile", matched));
        localRuntime.stop();
        LocalModelRuntime.Status st = body.get("download") != null
            && !"false".equalsIgnoreCase(str(body.get("download")))
            ? localRuntime.download() : localRuntime.status();
        return ApiResponse.ok("已切换到 " + matched + (st.modelInstalled() ? "（权重已就绪）" : "（尚未下载）"), st);
    }

    /**
     * 重启本地模型（改完参数用它生效）。
     */
    @PostMapping("/local/restart")
    public ApiResponse<LocalModelRuntime.Status> restartLocal() {
        log.info("用户请求重启本地模型（参数变更生效）");
        localRuntime.stop();
        LocalModelRuntime.Status st = localRuntime.start();
        if (!st.running()) {
            return new ApiResponse<>(false, "重启失败：" + st.lastError(), st, st.lastError());
        }
        return ApiResponse.ok("本地模型已按新参数重启", st);
    }

    /**
     * 只下载模型权重，不启动运行时（不占显存）。
     *
     * 安装包不再内置 8.9GB 权重，首次用到时由后端自动从 ModelScope 拉；
     * 这个接口是给界面「下载模型」按钮和"先下好再跑"的场景用的。
     */
    @PostMapping("/local/download")
    public ApiResponse<LocalModelRuntime.Status> downloadLocal() {
        log.info("用户手动请求下载本地模型权重");
        LocalModelRuntime.Status st = localRuntime.download();
        if (st.modelInstalled()) {
            return ApiResponse.ok("模型已就绪", st);
        }
        return new ApiResponse<>(false, "模型下载未完成：" + st.lastError(), st, st.lastError());
    }

    /**
     * 手动加载本地模型（想提前热机时用；正常情况下第一条消息会自动加载）
     */
    @PostMapping("/local/start")
    public ApiResponse<LocalModelRuntime.Status> startLocal() {
        log.info("用户手动请求加载本地模型");
        LocalModelRuntime.Status st = localRuntime.start();
        if (!st.running()) {
            return new ApiResponse<LocalModelRuntime.Status>(
                false, "本地模型启动失败", st, st.lastError());
        }
        return ApiResponse.ok("本地模型已就绪", st);
    }

    /**
     * 卸载本地模型并释放显存
     */
    @PostMapping("/local/stop")
    public ApiResponse<LocalModelRuntime.Status> stopLocal() {
        log.info("用户手动请求卸载本地模型");
        localRuntime.stop();
        return ApiResponse.ok("本地模型已卸载", localRuntime.status());
    }

    /**
     * 预热：把「系统提示词 + 工具定义」的前缀缓存提前算好。
     *
     * 纯 CPU 推理时预填充是算力瓶颈，冷启动第一条消息要等几分钟；
     * 预热把这段开销挪到用户提问之前，顺带实测出预填充速度。
     */
    @PostMapping("/warmup")
    public ApiResponse<Map<String, Object>> warmUp() {
        log.info("手动请求预热");
        Map<String, Object> r = prewarmService.warmUp("手动触发");
        boolean ok = Boolean.TRUE.equals(r.get("warmed"));
        return new ApiResponse<>(ok, ok ? "预热完成" : String.valueOf(r.get("reason")), r, null);
    }

    /**
     * 最近一次预热的结果
     */
    @GetMapping("/warmup")
    public ApiResponse<Map<String, Object>> warmUpResult() {
        return ApiResponse.ok(prewarmService.lastResult());
    }

    /**
     * 提示词体检：把这一轮真正会发给模型的系统提示词 + 工具定义原样吐出来。
     *
     * 存在的意义是「慢的问题要能被量化」——提示词多大、多少字符、和上一版比少了多少，
     * 都得有数，不能靠感觉说"优化过了"。用本地运行时的 /tokenize 把返回的文本
     * 数一遍，就是模型眼里真实的 token 数。
     *
     * 例：GET /api/runtime/prompt-preview?mode=standard&message=帮我改一下这个文件
     */
    @GetMapping("/prompt-preview")
    public ApiResponse<Map<String, Object>> promptPreview(
            @RequestParam(value = "mode", required = false, defaultValue = "standard") String modeName,
            @RequestParam(value = "message", required = false, defaultValue = "") String message,
            @RequestParam(value = "workspace", required = false, defaultValue = "") String workspace,
            @RequestParam(value = "full", required = false, defaultValue = "false") boolean full) {

        if (agentLoop == null) {
            return new ApiResponse<>(false, "AgentLoop 不可用（该接口只在完整应用里生效）", null, null);
        }
        com.lioncode.core.agent.AgentMode mode;
        try {
            mode = com.lioncode.core.agent.AgentMode.valueOf(modeName.trim().toUpperCase());
        } catch (Exception e) {
            return new ApiResponse<>(false, "未知模式: " + modeName, null, null);
        }
        String ws = workspace == null || workspace.isBlank()
            ? System.getProperty("user.dir")
            : workspace;
        String prompt = agentLoop.previewSystemPrompt(mode, ws, message);

        Map<String, Object> out = new HashMap<>();
        out.put("mode", mode.name());
        out.put("workspace", ws);
        out.put("nativeTools", agentLoop.previewUsesNativeTools());
        out.put("chars", prompt.length());
        out.put("lines", prompt.split("\n", -1).length);
        // 原生通道才会下发的 tools 定义：本地模式下现在是空数组（省掉的就这一坨）
        try {
            var defs = agentLoop.previewToolDefinitions(mode);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(defs);
            out.put("toolDefinitionsCount", defs.size());
            out.put("toolDefinitionsChars", json.length());
            if (full) {
                out.put("toolDefinitions", json);
            }
        } catch (Exception e) {
            out.put("toolDefinitionsError", String.valueOf(e.getMessage()));
        }
        // 默认只回前 400 字，够看结构；要看全文加 full=true（调提示词时才需要）
        out.put("head", prompt.length() > 400 ? prompt.substring(0, 400) : prompt);
        out.put("systemPrompt", full ? prompt : null);
        return ApiResponse.ok(out);
    }

    /**
     * 切换运行方式请求
     */
    public record ModeRequest(String mode, String baseUrl, String apiKey, String model) {}

    /**
     * 切换工具调用方式请求
     */
    public record ToolCallModeRequest(String mode) {}
}
