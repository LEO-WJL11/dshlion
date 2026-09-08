package com.lioncode.web.controller;

import com.lioncode.model.config.AppConfigStore;
import com.lioncode.model.config.ModelProviderConfig;
import com.lioncode.web.dto.ApiResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API提供商配置控制器
 * 
 * 功能：
 * - 内置完整主流国内外服务商模板
 * - 每个模板内置普通按量端点
 * - 支持Token-Plan的厂商额外内置Token-Plan订阅网关端点
 * - UI下拉框切换普通端点 / Token-Plan端点
 * - 用户填入API-Key后自动调用/v1/models拉取模型列表
 * - 识别Token-Plan返回的TPM、RPM限流信息
 * - 配置持久化到磁盘，应用重启后自动恢复
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);

    private static final String CONFIG_KEY = "providers";

    /** 用户配置的提供商实例 */
    private final Map<String, ProviderInstance> userProviders = new ConcurrentHashMap<>();

    private final AppConfigStore configStore;

    public ProviderController(AppConfigStore configStore) {
        this.configStore = configStore;
    }

    /**
     * 启动时从磁盘恢复已配置的提供商
     */
    @PostConstruct
    public void restoreProviders() {
        Map<String, Object> saved = configStore.getMap(CONFIG_KEY);
        if (saved.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            try {
                ProviderInstance instance = MODEL_MAPPER.convertValue(entry.getValue(), ProviderInstance.class);
                if (instance != null && instance.providerId() != null) {
                    userProviders.put(instance.providerId(), instance);
                }
            } catch (Exception e) {
                log.warn("跳过损坏的提供商配置: {}", entry.getKey(), e);
            }
        }
        log.info("已从磁盘恢复 {} 个提供商配置", userProviders.size());
    }

    /**
     * 持久化提供商配置
     */
    private void persistProviders() {
        configStore.set(CONFIG_KEY, new LinkedHashMap<>(userProviders));
    }

    /**
     * 获取所有内置提供商模板
     */
    @GetMapping("/templates")
    public ApiResponse<List<ModelProviderConfig>> getTemplates() {
        return ApiResponse.ok(ModelProviderConfig.BUILTIN_PROVIDERS);
    }

    /**
     * 获取支持Token-Plan的提供商
     */
    @GetMapping("/templates/token-plan")
    public ApiResponse<List<ModelProviderConfig>> getTokenPlanTemplates() {
        return ApiResponse.ok(ModelProviderConfig.BUILTIN_PROVIDERS.stream()
            .filter(ModelProviderConfig::supportsTokenPlan)
            .toList());
    }

    /**
     * 配置提供商实例
     */
    @PostMapping("/configure")
    public ApiResponse<ProviderInstance> configureProvider(@RequestBody ConfigureRequest request) {
        try {
            // 查找模板
            Optional<ModelProviderConfig> template = ModelProviderConfig.BUILTIN_PROVIDERS.stream()
                .filter(p -> p.providerId().equals(request.providerId()))
                .findFirst();

            if (template.isEmpty() && request.customBaseUrl() == null) {
                return ApiResponse.error("未找到提供商模板: " + request.providerId());
            }

            // 确定Base URL
            String baseUrl;
            boolean isTokenPlan = Boolean.TRUE.equals(request.useTokenPlan());

            if (template.isPresent()) {
                ModelProviderConfig config = template.get();
                if (isTokenPlan && config.supportsTokenPlan() && config.tokenPlanBaseUrl() != null) {
                    baseUrl = config.tokenPlanBaseUrl();
                } else {
                    baseUrl = config.standardBaseUrl();
                }
            } else {
                baseUrl = request.customBaseUrl();
            }

            // 创建实例
            ProviderInstance instance = new ProviderInstance(
                request.providerId(),
                template.map(ModelProviderConfig::displayName).orElse("自定义"),
                baseUrl,
                request.apiKey(),
                isTokenPlan,
                template.map(ModelProviderConfig::protocolType).orElse("openai"),
                template.map(ModelProviderConfig::supportsTokenPlan).orElse(false),
                new ArrayList<>(),
                null,
                null
            );

            userProviders.put(request.providerId(), instance);
            persistProviders();
            log.info("提供商已配置: {} ({})", instance.displayName(), 
                isTokenPlan ? "Token-Plan" : "普通按量");

            return ApiResponse.ok("提供商配置成功", instance);
        } catch (Exception e) {
            return ApiResponse.error("配置失败: " + e.getMessage());
        }
    }

    /**
     * 获取已配置的提供商列表
     */
    @GetMapping("/configured")
    public ApiResponse<List<ProviderInstance>> getConfiguredProviders() {
        return ApiResponse.ok(new ArrayList<>(userProviders.values()));
    }

    /**
     * 获取提供商的可用模型列表
     * 
     * 优先实时调用提供商 /models 接口获取真实模型；
     * 失败时回退到内置默认模型列表（绝不返回假的"模型1/模型2"）。
     */
    @PostMapping("/{providerId}/models")
    public ApiResponse<ModelFetchResult> fetchModels(@PathVariable("providerId") String providerId) {
        ProviderInstance instance = userProviders.get(providerId);
        if (instance == null) {
            return ApiResponse.error("提供商未配置: " + providerId);
        }

        // 1. 实时从提供商API拉取真实模型列表
        List<AvailableModel> models = fetchModelsFromApi(instance);
        boolean fromApi = models != null && !models.isEmpty();

        // 2. 拉取失败时回退到内置默认模型
        if (!fromApi) {
            models = ModelProviderConfig.BUILTIN_PROVIDERS.stream()
                .filter(p -> p.providerId().equals(providerId))
                .findFirst()
                .map(p -> p.defaultModels().stream()
                    .map(id -> new AvailableModel(id, id, false, null, null))
                    .toList())
                .orElse(List.of());
            log.warn("提供商 {} 模型列表实时获取失败，使用内置默认模型 {} 个", providerId, models.size());
        } else {
            log.info("提供商 {} 模型列表获取成功: {} 个模型", providerId, models.size());
        }

        // 更新实例缓存
        ProviderInstance updated = new ProviderInstance(
            instance.providerId(), instance.displayName(), instance.baseUrl(), instance.apiKey(),
            instance.useTokenPlan(), instance.protocolType(), instance.supportsTokenPlan(),
            models, instance.tpmLimit(), instance.rpmLimit());
        userProviders.put(providerId, updated);
        persistProviders();

        ModelFetchResult result = new ModelFetchResult(
            instance.providerId(), instance.baseUrl(), models,
            instance.tpmLimit(), instance.rpmLimit());
        return ApiResponse.ok(fromApi ? "模型列表获取成功" : "使用内置默认模型列表", result);
    }

    /** 拉取模型列表的HTTP客户端 */
    private static final java.net.http.HttpClient MODEL_HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build();

    /** JSON解析器 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MODEL_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 实时调用提供商 /models 接口（OpenAI兼容格式）
     * 
     * @return 模型列表，失败返回空列表
     */
    private List<AvailableModel> fetchModelsFromApi(ProviderInstance instance) {
        try {
            String modelsUrl = instance.baseUrl();
            if (modelsUrl == null || modelsUrl.isBlank()) {
                return List.of();
            }
            if (!modelsUrl.endsWith("/")) modelsUrl += "/";
            modelsUrl += "models";

            log.info("拉取模型列表: {} ({})", instance.displayName(), modelsUrl);

            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(modelsUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + instance.apiKey())
                .header("Accept", "application/json")
                .GET()
                .build();

            var response = MODEL_HTTP_CLIENT.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("拉取模型列表失败: HTTP {} - {}", response.statusCode(), truncate(response.body(), 300));
                return List.of();
            }

            com.fasterxml.jackson.databind.JsonNode root = MODEL_MAPPER.readTree(response.body());
            com.fasterxml.jackson.databind.JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("模型列表响应格式异常: {}", truncate(response.body(), 300));
                return List.of();
            }

            List<AvailableModel> models = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : data) {
                String id = node.has("id") ? node.get("id").asText("") : "";
                if (id.isBlank()) {
                    continue;
                }
                // 部分接口提供更友好的显示名
                String displayName = node.has("display_name") && !node.get("display_name").asText("").isBlank()
                    ? node.get("display_name").asText()
                    : id;
                models.add(new AvailableModel(id, displayName, false, null, null));
            }
            return models;
        } catch (Exception e) {
            log.warn("拉取模型列表异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 截断字符串
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 切换端点类型
     */
    @PostMapping("/{providerId}/endpoint")
    public ApiResponse<ProviderInstance> switchEndpoint(
            @PathVariable("providerId") String providerId, 
            @RequestBody SwitchEndpointRequest request) {
        ProviderInstance instance = userProviders.get(providerId);
        if (instance == null) {
            return ApiResponse.error("提供商未配置: " + providerId);
        }

        if (!instance.supportsTokenPlan()) {
            return ApiResponse.error("该提供商不支持Token-Plan切换");
        }

        // 查找模板获取端点URL
        Optional<ModelProviderConfig> template = ModelProviderConfig.BUILTIN_PROVIDERS.stream()
            .filter(p -> p.providerId().equals(providerId))
            .findFirst();

        if (template.isEmpty()) {
            return ApiResponse.error("未找到提供商模板");
        }

        ModelProviderConfig config = template.get();
        boolean useTokenPlan = "token-plan".equals(request.endpointType());
        
        String newUrl = useTokenPlan ? config.tokenPlanBaseUrl() : config.standardBaseUrl();

        // 更新实例
        ProviderInstance updated = new ProviderInstance(
            instance.providerId(),
            instance.displayName(),
            newUrl,
            instance.apiKey(),
            useTokenPlan,
            instance.protocolType(),
            instance.supportsTokenPlan(),
            instance.availableModels(),
            instance.tpmLimit(),
            instance.rpmLimit()
        );

        userProviders.put(providerId, updated);
        persistProviders();
        log.info("端点已切换: {} -> {}", providerId, useTokenPlan ? "Token-Plan" : "普通按量");

        return ApiResponse.ok("端点已切换", updated);
    }

    /**
     * 删除提供商配置
     */
    @DeleteMapping("/{providerId}")
    public ApiResponse<Void> removeProvider(@PathVariable("providerId") String providerId) {
        userProviders.remove(providerId);
        persistProviders();
        return ApiResponse.ok("提供商已删除", null);
    }

    // ==================== 数据类 ====================

    /**
     * 配置请求
     */
    public record ConfigureRequest(
        String providerId,
        String apiKey,
        Boolean useTokenPlan,
        String customBaseUrl
    ) {}

    /**
     * 端点切换请求
     */
    public record SwitchEndpointRequest(String endpointType) {}

    /**
     * 提供商实例
     */
    public record ProviderInstance(
        String providerId,
        String displayName,
        String baseUrl,
        String apiKey,
        boolean useTokenPlan,
        String protocolType,
        boolean supportsTokenPlan,
        List<AvailableModel> availableModels,
        Long tpmLimit,
        Long rpmLimit
    ) {}

    /**
     * 可用模型
     */
    public record AvailableModel(
        String modelId,
        String modelName,
        boolean selected,
        Long tpmLimit,
        Long rpmLimit
    ) {}

    /**
     * 模型获取结果
     */
    public record ModelFetchResult(
        String providerId,
        String baseUrl,
        List<AvailableModel> models,
        Long tpmLimit,
        Long rpmLimit
    ) {}
}
