package com.lioncode.model.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用配置存储（持久化）
 * 
 * 持久化用户级配置：模型提供商（baseUrl）、适配器配置、
 * 当前激活的适配器、选中的模型与思考等级。
 * 
 * 盒子出厂即默认指向本地模型运行时，无需任何API-Key，也不连接云端服务商。
 * 
 * 存储位置: {user.home}/.lioncode/app-config.json
 * 每次修改立即落盘，应用重启后自动加载。
 */
@Component
public class AppConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AppConfigStore.class);
    private static final ObjectMapper mapper = createMapper();

    /** 本地模型运行时Base URL（与 application.yml 的 lionbox.runtime 保持一致） */
    @Value("${lionbox.runtime.base-url:http://127.0.0.1:8788/v1}")
    private String localBaseUrl;

    /** 盒子出厂默认模型 */
    private static final String LOCAL_PROVIDER_ID = "lionbox-local";
    private static final String CUSTOM_PROVIDER_ID = "lionbox-custom";
    private static final String LOCAL_MODEL = "lion-models1";
    /**
     * 历史配置里写下的模型名。
     * 早期版本在界面上直接暴露了底座模型名，现在统一叫 lion-models1，
     * 老配置靠 {@link #migrateLegacyModelName()} 迁移，不要删。
     */
    private static final String LEGACY_LOCAL_MODEL = "MiMo-V2.6-Distill-Qwen-9B";

    /** 运行模式：用随盒子交付的本地模型 */
    public static final String MODE_LOCAL = "local";
    /** 运行模式：用用户自己填的 OpenAI 兼容 API */
    public static final String MODE_CUSTOM = "custom";

    /**
     * 工具调用方式：自动（推荐）
     *   自定义 API → 原生 function calling；本地 GGUF 运行时 → 文本 &lt;tool_call&gt; 约定。
     *   依据是两种端点的实际能力：本地 llama-server 未启用 --jinja，请求里的 tools
     *   会被直接忽略，而本地模型又是按文本约定微调的。
     */
    public static final String TOOLCALL_AUTO = "auto";
    /** 工具调用方式：强制原生 function calling（下发 tools，让模型走 API 工具通道） */
    public static final String TOOLCALL_NATIVE = "native";
    /** 工具调用方式：强制文本约定（不下发 tools，完全靠系统提示词里的 &lt;tool_call&gt; 格式） */
    public static final String TOOLCALL_TEXT = "text";

    private final Map<String, Object> config = new ConcurrentHashMap<>();
    private Path configFile;

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @PostConstruct
    public void init() {
        configFile = Path.of(System.getProperty("user.home"), ".lioncode", "app-config.json");
        try {
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile);
                Map<String, Object> loaded = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
                if (loaded != null) {
                    config.putAll(loaded);
                }
                log.info("应用配置已从磁盘加载: {} ({}项)", configFile, config.size());
            } else {
                log.info("未找到配置文件，使用默认配置: {}", configFile);
            }
        } catch (IOException e) {
            log.error("加载应用配置失败: {}", configFile, e);
        }
        applyDefaults();
    }

    /**
     * 施加默认配置（两种运行模式共存）
     *
     * 两条规则，别搞混：
     *
     * 【本地模式 local】
     *   端点与模型固定指向随盒子交付的运行时，密钥留空。
     *   应用启动时**不加载模型**，等第一条消息由 LocalModelRuntime 惰性拉起。
     *
     * 【自定义模式 custom】
     *   用户自己填的 baseUrl / apiKey / model **原样保留，绝不覆盖、绝不抹除**。
     *   这种模式下本地模型永远不会被加载。
     *
     * 老版本（只支持本地）留下的配置里没有 providerMode 键，
     * 这里按现有端点推断：回环地址=本地，其它非空地址=用户自己配的 API。
     */
    private void applyDefaults() {
        String localUrl = localBaseUrl != null && !localBaseUrl.isBlank()
            ? localBaseUrl
            : ModelProviderConfig.BUILTIN_PROVIDERS.get(0).standardBaseUrl();
        boolean changed = false;

        // ---- 1) 确定运行模式 ----
        String mode = str(config.get("providerMode"));
        if (!MODE_LOCAL.equals(mode) && !MODE_CUSTOM.equals(mode)) {
            String cur = str(config.get("baseUrl"));
            if (cur.isBlank()) {
                cur = str(getMap("openai").get("baseUrl"));
            }
            mode = (!cur.isBlank() && !isKnownLocalBaseUrl(cur)) ? MODE_CUSTOM : MODE_LOCAL;
            log.info("未发现运行模式配置，按现有端点推断为: {}（端点={}）", mode, cur);
            changed = true;
        }
        if (!mode.equals(str(config.get("providerMode")))) {
            config.put("providerMode", mode);
            changed = true;
        }

        // ---- 1.5) 历史配置里的模型名迁移（老版本暴露的是底座模型名）----
        changed |= migrateLegacyModelName();

        // ---- 2) 按模式落配置 ----
        if (MODE_CUSTOM.equals(mode)) {
            Map<String, Object> block = getMap("openai");
            String url = firstNonBlank(str(config.get("baseUrl")), str(block.get("baseUrl")));
            String model = firstNonBlank(str(config.get("model")), str(block.get("model")));
            String key = firstNonBlank(str(config.get("apiKey")), str(block.get("apiKey")));
            if (url.isBlank()) {
                log.warn("运行模式是自定义 API，但没有填端点，自动退回本地模型");
                mode = MODE_LOCAL;
                config.put("providerMode", MODE_LOCAL);
            } else {
                changed |= putIfDifferent("provider", CUSTOM_PROVIDER_ID);
                changed |= putIfDifferent("baseUrl", url);
                changed |= putIfDifferent("model", model);
                changed |= putIfDifferent("apiKey", key);
                changed |= writeAdapterBlock(url, model, key);
                log.info("运行模式=自定义 API: baseUrl={}, model={}, 已配置密钥={}",
                    url, model, !key.isBlank());
            }
        }
        if (MODE_LOCAL.equals(mode)) {
            changed |= putIfDifferent("provider", LOCAL_PROVIDER_ID);
            changed |= putIfDifferent("baseUrl", localUrl);
            changed |= putIfDifferent("model", LOCAL_MODEL);
            changed |= putIfDifferent("apiKey", "");
            changed |= writeAdapterBlock(localUrl, LOCAL_MODEL, "");
        }

        // ---- 3) 协议固定走 OpenAI 兼容 ----
        if (!"OPENAI_COMPATIBLE".equals(config.get("activeAdapter"))) {
            config.put("activeAdapter", "OPENAI_COMPATIBLE");
            changed = true;
        }

        // ---- 3.5) 工具调用方式（老配置没有这个键 → 补成 auto）----
        String toolCall = str(config.get("toolCallMode")).trim().toLowerCase();
        if (!TOOLCALL_NATIVE.equals(toolCall) && !TOOLCALL_TEXT.equals(toolCall)) {
            if (!TOOLCALL_AUTO.equals(toolCall)) {
                log.info("工具调用方式未配置或非法（{}），回落到 auto", toolCall);
            }
            changed |= putIfDifferent("toolCallMode", TOOLCALL_AUTO);
        }

        // ---- 4) 两个内置实例都登记好，用户已有的实例不删 ----
        // 「自定义 API」实例要用**存档里**的用户端点来初始化。
        // 早先这里传的是当前生效的 baseUrl，默认就等于本地端点，于是新建的配置里
        // 自定义实例指向了 127.0.0.1:8788 —— 而自定义模式下本地运行时根本不启动，
        // 用户切过去没改地址就会直接连不上，还很难反应过来是自己没填。
        Map<String, Object> customArchive = getMap("customApi");
        changed |= ensureProviderInstance(LOCAL_PROVIDER_ID, "LionBox 本地模型", localUrl, LOCAL_MODEL);
        changed |= ensureProviderInstance(CUSTOM_PROVIDER_ID, "自定义 API",
            str(customArchive.get("baseUrl")), str(customArchive.get("model")));
        changed |= repairCustomProviderPointingAtLocal(customArchive);

        if (changed) {
            saveToDisk();
            log.info("模型配置已更新: mode={}, provider={}, baseUrl={}, model={}",
                config.get("providerMode"), config.get("provider"), config.get("baseUrl"),
                config.get("model"));
        }
    }

    /**
     * 把历史配置里残留的底座模型名改成 lion-models1。
     *
     * 生效中的 model / openai.model 两个键在本地模式下本来就由第 2 步强制覆盖，
     * 真正会漏下来的是 providers 实例里登记的可选模型列表——界面读的就是它，
     * 所以老配置升级后下拉框里还会显示底座名字。
     *
     * 只动盒子自己那两个实例（本地实例、或端点仍是本地端点的实例）：
     * 自定义模式下用户完全可能自己就填了这个模型名，那是他的配置，不能改。
     */
    @SuppressWarnings("unchecked")
    private boolean migrateLegacyModelName() {
        Object raw = config.get("providers");
        if (!(raw instanceof Map)) {
            return false;
        }
        Map<String, Object> providers = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        boolean changed = false;
        for (Map.Entry<String, Object> entry : providers.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> instance = new java.util.LinkedHashMap<>(
                (Map<String, Object>) entry.getValue());
            boolean isBoxInstance = LOCAL_PROVIDER_ID.equals(entry.getKey())
                || isKnownLocalBaseUrl(instance.get("baseUrl"));
            if (!isBoxInstance) {
                continue;
            }
            boolean touched = false;

            if (LEGACY_LOCAL_MODEL.equals(str(instance.get("model")))) {
                instance.put("model", LOCAL_MODEL);
                touched = true;
            }
            Object models = instance.get("availableModels");
            if (models instanceof java.util.List<?> list) {
                java.util.List<Object> fixed = new java.util.ArrayList<>(list.size());
                for (Object item : list) {
                    if (!(item instanceof Map)) {
                        fixed.add(item);
                        continue;
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>((Map<String, Object>) item);
                    for (String field : new String[] {"modelId", "modelName"}) {
                        if (LEGACY_LOCAL_MODEL.equals(str(model.get(field)))) {
                            model.put(field, LOCAL_MODEL);
                            touched = true;
                        }
                    }
                    fixed.add(model);
                }
                if (touched) {
                    instance.put("availableModels", fixed);
                }
            }
            if (touched) {
                providers.put(entry.getKey(), instance);
                changed = true;
            }
        }
        if (changed) {
            config.put("providers", providers);
            log.info("历史配置中的模型名已更新：{} → {}", LEGACY_LOCAL_MODEL, LOCAL_MODEL);
        }
        return changed;
    }

    private String str(Object o) {
        return o instanceof String s ? s : (o == null ? "" : String.valueOf(o));
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }

    /** 有变化才写，避免每次启动都落盘 */
    private boolean putIfDifferent(String key, String value) {
        String cur = str(config.get(key));
        if (value != null && !value.equals(cur)) {
            config.put(key, value);
            return true;
        }
        return false;
    }

    /**
     * 同步适配器级配置块。
     * ChatController 读写的就是这个块（key = "openai"），
     * 不保持同步的话界面上改了配置、实际请求还用旧的。
     */
    @SuppressWarnings("unchecked")
    private boolean writeAdapterBlock(String url, String model, String key) {
        Map<String, Object> block = new java.util.LinkedHashMap<>(getMap("openai"));
        boolean changed = false;
        if (!url.equals(str(block.get("baseUrl")))) {
            block.put("baseUrl", url);
            changed = true;
        }
        if (!model.equals(str(block.get("model")))) {
            block.put("model", model);
            changed = true;
        }
        if (!key.equals(str(block.get("apiKey")))) {
            block.put("apiKey", key);
            changed = true;
        }
        if (changed) {
            config.put("openai", block);
        }
        return changed;
    }

    /**
     * 修复历史配置：「自定义 API」实例的 baseUrl 被初始化成了本地端点。
     *
     * 自定义实例指向本地端点是一个永远不会工作的组合——那个端点在自定义模式下不启动。
     * 所以一旦发现，就换成用户存档里的端点；存档也是空的话就留空，
     * 让界面把输入框空着提示用户填写，而不是给他一个看似填好的错误地址。
     */
    @SuppressWarnings("unchecked")
    private boolean repairCustomProviderPointingAtLocal(Map<String, Object> customArchive) {
        Object raw = config.get("providers");
        if (!(raw instanceof Map)) {
            return false;
        }
        Map<String, Object> providers = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        Object inst = providers.get(CUSTOM_PROVIDER_ID);
        if (!(inst instanceof Map)) {
            return false;
        }
        Map<String, Object> instance = new java.util.LinkedHashMap<>((Map<String, Object>) inst);
        if (!isKnownLocalBaseUrl(instance.get("baseUrl"))) {
            return false;
        }
        String url = str(customArchive.get("baseUrl"));
        String model = str(customArchive.get("model"));
        instance.put("baseUrl", url);
        instance.put("availableModels", model.isBlank()
            ? java.util.List.of()
            : java.util.List.of(Map.of("modelId", model, "modelName", model, "selected", false)));
        providers.put(CUSTOM_PROVIDER_ID, instance);
        config.put("providers", providers);
        log.info("已修正「自定义 API」实例的错误端点（原本指向本地端点），现在为：{}",
            url.isBlank() ? "(空，等待用户填写)" : url);
        return true;
    }

    /** 确保 providers 里登记了某个内置实例；已存在就不动，保留用户填的密钥 */
    @SuppressWarnings("unchecked")
    private boolean ensureProviderInstance(String id, String display, String url, String model) {
        Map<String, Object> providers;
        Object raw = config.get("providers");
        if (raw instanceof Map) {
            providers = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        } else {
            providers = new java.util.LinkedHashMap<>();
        }
        if (providers.containsKey(id)) {
            return false;   // 已有实例：不覆盖用户的端点/密钥
        }
        Map<String, Object> instance = new java.util.LinkedHashMap<>();
        instance.put("providerId", id);
        instance.put("displayName", display);
        instance.put("baseUrl", url == null ? "" : url);
        instance.put("apiKey", "");
        instance.put("useTokenPlan", false);
        instance.put("protocolType", "openai");
        instance.put("supportsTokenPlan", false);
        instance.put("availableModels", model == null || model.isBlank()
            ? java.util.List.of()
            : java.util.List.of(Map.of("modelId", model, "modelName", model, "selected", false)));
        providers.put(id, instance);
        config.put("providers", providers);
        return true;
    }

    /**
     * 判断给定Base URL是否属于盒子本地端点
     */
    private boolean isKnownLocalBaseUrl(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return false;
        }
        for (ModelProviderConfig provider : ModelProviderConfig.BUILTIN_PROVIDERS) {
            if (s.equals(provider.standardBaseUrl())) {
                return true;
            }
        }
        String lower = s.toLowerCase();
        return lower.contains("://127.0.0.1") || lower.contains("://localhost")
            || lower.contains("://[::1]");
    }

    /**
     * 获取配置项
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = config.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 获取配置项（Map类型）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object value = config.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * 工具调用方式（已归一化，永远不会返回非法值）
     */
    public String toolCallMode() {
        String v = str(config.get("toolCallMode")).trim().toLowerCase();
        if (TOOLCALL_NATIVE.equals(v) || TOOLCALL_TEXT.equals(v)) {
            return v;
        }
        return TOOLCALL_AUTO;
    }

    /** 当前是否在用随盒子交付的本地模型（本地模式下端点是我们自己拉起的 llama-server） */
    public boolean isLocalMode() {
        return MODE_LOCAL.equals(str(config.get("providerMode")).trim().toLowerCase());
    }

    /**
     * 校验外部传入的工具调用方式，非法值返回 null（调用方据此报错）
     */
    public static String normalizeToolCallMode(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        return switch (v) {
            case TOOLCALL_AUTO, TOOLCALL_NATIVE, TOOLCALL_TEXT -> v;
            default -> null;
        };
    }

    /**
     * 设置配置项并立即持久化
     */
    public synchronized void set(String key, Object value) {
        if (value == null) {
            config.remove(key);
        } else {
            config.put(key, value);
        }
        saveToDisk();
    }

    /**
     * 批量更新配置并立即持久化
     */
    public synchronized void update(Map<String, Object> updates) {
        config.putAll(updates);
        saveToDisk();
    }

    /**
     * 完整配置快照
     */
    public Map<String, Object> snapshot() {
        return new HashMap<>(config);
    }

    /**
     * 持久化到磁盘
     */
    private void saveToDisk() {
        try {
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFile, json);
            log.debug("应用配置已保存: {}", configFile);
        } catch (IOException e) {
            log.error("保存应用配置失败: {}", configFile, e);
        }
    }
}
