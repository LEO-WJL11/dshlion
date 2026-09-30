package com.lioncode.model.adapter;

import com.lioncode.core.event.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 适配器管理器
 * 
 * 管理所有模型协议适配器的注册、切换和状态保护。
 * 出厂固定使用OpenAI兼容协议适配器对接本地模型运行时。
 * 切换过程保护关键会话状态。
 */
@Component
public class AdapterManager {

    private static final Logger log = LoggerFactory.getLogger(AdapterManager.class);

    private final Map<ModelAdapter.AdapterType, ModelAdapter> adapters = new ConcurrentHashMap<>();
    private final EventStore eventStore;

    /** 当前活跃适配器 */
    private volatile ModelAdapter activeAdapter;

    public AdapterManager(OpenAICompatibleAdapter openAIAdapter, 
                          AnthropicAdapter anthropicAdapter,
                          EventStore eventStore) {
        this.eventStore = eventStore;
        
        // 注册内置适配器
        adapters.put(ModelAdapter.AdapterType.OPENAI_COMPATIBLE, openAIAdapter);
        adapters.put(ModelAdapter.AdapterType.ANTHROPIC, anthropicAdapter);
        
        // 默认使用OpenAI兼容适配器
        this.activeAdapter = openAIAdapter;
        
        log.info("适配器管理器已初始化，当前适配器: {}", activeAdapter.getName());
    }

    /**
     * 获取当前活跃适配器
     */
    public ModelAdapter getActiveAdapter() {
        return activeAdapter;
    }

    /**
     * 恢复激活适配器（启动恢复/配置保存场景）
     * 不做可用性检查：即使端点尚未配置也先设为激活，
     * 配置保存后即生效。
     */
    public boolean restoreActiveAdapter(ModelAdapter.AdapterType type) {
        ModelAdapter adapter = adapters.get(type);
        if (adapter == null) {
            log.error("未找到适配器类型: {}", type);
            return false;
        }
        ModelAdapter old = this.activeAdapter;
        this.activeAdapter = adapter;
        if (old != adapter) {
            log.info("激活适配器已恢复/切换: {} -> {}", old.getName(), adapter.getName());
        }
        return true;
    }

    /**
     * 切换适配器
     * 切换过程保护关键会话状态
     */
    public synchronized boolean switchAdapter(ModelAdapter.AdapterType type, String sessionId) {
        ModelAdapter newAdapter = adapters.get(type);
        if (newAdapter == null) {
            log.error("未找到适配器类型: {}", type);
            return false;
        }

        ModelAdapter oldAdapter = this.activeAdapter;
        
        // 检查新适配器是否可用
        if (!newAdapter.isAvailable()) {
            log.warn("目标适配器不可用: {}", type);
            return false;
        }

        // 记录切换事件
        eventStore.recordEvent(sessionId, 
            com.lioncode.core.event.LionEvent.EventType.ADAPTER_SWITCH,
            Map.of(
                "fromAdapter", oldAdapter.getName(),
                "toAdapter", newAdapter.getName(),
                "fromType", oldAdapter.getType().name(),
                "toType", type.name()
            ),
            String.format("适配器切换: %s -> %s", oldAdapter.getName(), newAdapter.getName())
        );

        // 执行切换
        this.activeAdapter = newAdapter;
        log.info("适配器已切换: {} -> {}", oldAdapter.getName(), newAdapter.getName());
        return true;
    }

    /**
     * 根据类型获取适配器
     */
    public Optional<ModelAdapter> getAdapter(ModelAdapter.AdapterType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    /**
     * 获取所有已注册适配器
     */
    public List<ModelAdapter> getAllAdapters() {
        return List.copyOf(adapters.values());
    }

    /**
     * 检查适配器是否可用
     */
    public boolean isAdapterAvailable(ModelAdapter.AdapterType type) {
        ModelAdapter adapter = adapters.get(type);
        return adapter != null && adapter.isAvailable();
    }

    /**
     * 更新适配器配置
     */
    public void updateAdapterConfig(ModelAdapter.AdapterType type, Map<String, Object> config) {
        ModelAdapter adapter = adapters.get(type);
        if (adapter != null) {
            adapter.updateConfig(config);
            log.info("适配器配置已更新: {}", type);
        }
    }
}
