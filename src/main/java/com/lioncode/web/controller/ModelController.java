package com.lioncode.web.controller;

import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.adapter.ModelInfo;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型配置控制器
 * 
 * 提供模型列表、思考等级等配置信息
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final AdapterManager adapterManager;

    public ModelController(AdapterManager adapterManager) {
        this.adapterManager = adapterManager;
    }

    /**
     * 获取当前适配器的可用模型列表
     */
    @GetMapping
    public ApiResponse<List<ModelInfo>> getModels() {
        ModelAdapter adapter = adapterManager.getActiveAdapter();
        if (adapter == null || !adapter.isAvailable()) {
            return ApiResponse.error("适配器未配置或不可用");
        }
        return ApiResponse.ok(adapter.getAvailableModels());
    }

    /**
     * 获取指定适配器的可用模型列表
     */
    @GetMapping("/{adapterType}")
    public ApiResponse<List<ModelInfo>> getModelsByAdapter(
            @PathVariable("adapterType") ModelAdapter.AdapterType adapterType) {
        return adapterManager.getAdapter(adapterType)
            .map(adapter -> {
                if (!adapter.isAvailable()) {
                    return ApiResponse.<List<ModelInfo>>error("适配器不可用");
                }
                return ApiResponse.ok(adapter.getAvailableModels());
            })
            .orElse(ApiResponse.error("未找到适配器: " + adapterType));
    }

    /**
     * 获取指定模型的思考等级选项
     * 
     * 前端根据选择的模型动态调用此接口获取可用的思考等级
     */
    @GetMapping("/thinking-levels")
    public ApiResponse<List<ModelInfo.ThinkingLevelOption>> getThinkingLevels(
            @RequestParam("modelId") String modelId,
            @RequestParam(value = "adapterType", required = false) ModelAdapter.AdapterType adapterType) {
        
        // 如果未指定适配器，使用当前活跃适配器
        ModelAdapter adapter;
        if (adapterType != null) {
            adapter = adapterManager.getAdapter(adapterType).orElse(null);
        } else {
            adapter = adapterManager.getActiveAdapter();
        }
        
        if (adapter == null || !adapter.isAvailable()) {
            return ApiResponse.error("适配器未配置或不可用");
        }

        // 查找指定模型
        List<ModelInfo> models = adapter.getAvailableModels();
        ModelInfo targetModel = models.stream()
            .filter(m -> m.id().equals(modelId))
            .findFirst()
            .orElse(null);

        if (targetModel == null) {
            return ApiResponse.error("未找到模型: " + modelId);
        }

        // 返回该模型支持的思考等级
        List<ModelInfo.ThinkingLevelOption> levels = targetModel.thinkingLevels();
        if (levels == null || levels.isEmpty()) {
            // 不支持思考等级的模型返回空列表
            return ApiResponse.ok(List.of());
        }

        return ApiResponse.ok(levels);
    }

    /**
     * 获取模型详情（包含思考等级信息）
     */
    @GetMapping("/detail")
    public ApiResponse<ModelInfo> getModelDetail(
            @RequestParam("modelId") String modelId,
            @RequestParam(value = "adapterType", required = false) ModelAdapter.AdapterType adapterType) {
        
        ModelAdapter adapter;
        if (adapterType != null) {
            adapter = adapterManager.getAdapter(adapterType).orElse(null);
        } else {
            adapter = adapterManager.getActiveAdapter();
        }
        
        if (adapter == null || !adapter.isAvailable()) {
            return ApiResponse.error("适配器未配置或不可用");
        }

        List<ModelInfo> models = adapter.getAvailableModels();
        ModelInfo targetModel = models.stream()
            .filter(m -> m.id().equals(modelId))
            .findFirst()
            .orElse(null);

        if (targetModel == null) {
            return ApiResponse.error("未找到模型: " + modelId);
        }

        return ApiResponse.ok(targetModel);
    }

    /**
     * 获取本地GGUF模型列表
     */
    @GetMapping("/local")
    public ApiResponse<List<ModelInfo>> getLocalModels() {
        return adapterManager.getAdapter(ModelAdapter.AdapterType.OPENAI_COMPATIBLE)
            .map(adapter -> {
                if (!adapter.isAvailable()) {
                    return ApiResponse.<List<ModelInfo>>error("适配器不可用");
                }
                List<ModelInfo> localModels = adapter.getAvailableModels().stream()
                    .filter(m -> m.source() == ModelInfo.ModelSource.LOCAL_GGUF)
                    .toList();
                return ApiResponse.ok(localModels);
            })
            .orElse(ApiResponse.error("未找到适配器"));
    }

    /**
     * 获取云端API模型列表
     */
    @GetMapping("/cloud")
    public ApiResponse<List<ModelInfo>> getCloudModels() {
        return adapterManager.getAdapter(ModelAdapter.AdapterType.OPENAI_COMPATIBLE)
            .map(adapter -> {
                if (!adapter.isAvailable()) {
                    return ApiResponse.<List<ModelInfo>>error("适配器不可用");
                }
                List<ModelInfo> cloudModels = adapter.getAvailableModels().stream()
                    .filter(m -> m.source() != ModelInfo.ModelSource.LOCAL_GGUF)
                    .toList();
                return ApiResponse.ok(cloudModels);
            })
            .orElse(ApiResponse.error("未找到适配器"));
    }
}
