package com.lioncode.model.config;

import java.util.List;
import java.util.Map;

/**
 * 模型提供商配置
 * 
 * 本产品的模型运行时就用本机自带的本地运行时，绑定回环地址，
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
    /** 本地模型运行时端口（与 application.yml 的 lionbox.runtime.port 保持一致） */
    public static final int LOCAL_RUNTIME_PORT = 8788;

    /** 本地模型运行时主机（仅回环地址） */
    public static final String LOCAL_RUNTIME_HOST = "127.0.0.1";

    /** 内置本地模型名称 */
    public static final String LOCAL_MODEL_NAME = "lion-models1";

    /** 内置模型文件（软件自带的GGUF权重） */
    public static final String LOCAL_MODEL_FILE = "lion-merged-Q8_0.gguf";

    /**
     * 本地运行时默认Base URL（端口与 application.yml 的 lionbox.runtime.port 一致）
     */
    public static String localBaseUrl() {
        return "http://" + LOCAL_RUNTIME_HOST + ":" + LOCAL_RUNTIME_PORT + "/v1";
    }

    /**
     * 内置提供商模板列表
     *
     * <p>【为什么加 final】它原来是 {@code public static}（非 final）的**可变静态字段**：
     * 任何代码都能把它整体换掉，而 ProviderController 的模板查找、AppConfigStore 的
     * 本地端点判定都依赖它 —— 换掉之后行为会变得无法解释。加 final 是源码兼容的
     * （全项目没有任何一处给它赋值），只是堵住这条路。
     *
     * 刻意只保留一项：软件自带的本地模型运行时。
     * 模型在本机以 GGUF 形式本地推理，通过回环地址的
     * OpenAI兼容端点提供服务，不需要API-Key，也不允许连接任何云端服务商。
     * 因此该列表不提供、也不再新增任何云端厂商模板。
     */
    public static final List<ModelProviderConfig> BUILTIN_PROVIDERS = List.of(
        new ModelProviderConfig("lionbox-local", "LionBox 本地模型",
            localBaseUrl(),
            null, false,
            List.of("lion-models1"), "openai")
    );
}
