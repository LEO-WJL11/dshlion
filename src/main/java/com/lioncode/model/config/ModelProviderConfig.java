package com.lioncode.model.config;

import java.util.List;
import java.util.Map;

/**
 * 模型提供商配置
 * 
 * 本产品为硬件一体机（模型盒子），模型运行时随盒子交付并绑定回环地址，
 * 因此内置模板列表刻意只保留一个本地提供商，不再包含任何云端服务商。
 * 字段结构（含Token-Plan相关字段）为兼容既有调用方而保持不变。
 */
public record ModelProviderConfig(
    /** 提供商ID */
    String providerId,
    /** 提供商显示名称 */
    String displayName,
    /** 普通按量端点Base URL */
    String standardBaseUrl,
    /** Token-Plan端点Base URL（不支持则为null） */
    String tokenPlanBaseUrl,
    /** 是否支持Token-Plan */
    boolean supportsTokenPlan,
    /** 默认模型列表 */
    List<String> defaultModels,
    /** 协议类型 */
    String protocolType
) {
    /** 盒子本地模型运行时端口（与 application.yml 的 lionbox.runtime.port 保持一致） */
    public static final int LOCAL_RUNTIME_PORT = 8788;

    /** 盒子本地模型运行时主机（仅回环地址） */
    public static final String LOCAL_RUNTIME_HOST = "127.0.0.1";

    /** 盒子内置模型名称 */
    public static final String LOCAL_MODEL_NAME = "MiMo-V2.6-Distill-Qwen-9B";

    /** 盒子内置模型文件（随盒子交付的GGUF权重） */
    public static final String LOCAL_MODEL_FILE = "lion-merged-Q8_0.gguf";

    /**
     * 盒子本地运行时默认Base URL（端口与 application.yml 的 lionbox.runtime.port 一致）
     */
    public static String localBaseUrl() {
        return "http://" + LOCAL_RUNTIME_HOST + ":" + LOCAL_RUNTIME_PORT + "/v1";
    }

    /**
     * 内置提供商模板列表
     * 
     * 刻意只保留一项：随盒子交付的本地模型运行时。
     * 本产品为硬件一体机，模型在盒子内以GGUF形式本地推理，通过回环地址的
     * OpenAI兼容端点提供服务，不需要API-Key，也不允许连接任何云端服务商。
     * 因此该列表不提供、也不再新增任何云端厂商模板。
     */
    public static List<ModelProviderConfig> BUILTIN_PROVIDERS = List.of(
        new ModelProviderConfig("lionbox-local", "LionBox 本地模型",
            localBaseUrl(),
            null, false,
            List.of("MiMo-V2.6-Distill-Qwen-9B"), "openai")
    );
}
