package com.lioncode.core.session;

import com.lioncode.core.workspace.WorkspaceManager;
import com.lioncode.model.adapter.AdapterManager;
import com.lioncode.model.adapter.ModelAdapter;
import com.lioncode.model.config.AppConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动状态恢复器
 * 
 * 应用启动时执行：
 * 1. 从磁盘恢复所有会话（含绑定的工作区）
 * 2. 恢复模型适配器的 baseUrl 配置（指向盒子本地运行时）
 * 3. 恢复上次激活的适配器
 * 
 * 对话历史由 ConversationHistory 自行恢复。
 */
@Component
public class StartupRestorer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRestorer.class);

    private final SessionPersistence sessionPersistence;
    private final SessionManager sessionManager;
    private final WorkspaceManager workspaceManager;
    private final AdapterManager adapterManager;
    private final AppConfigStore configStore;

    public StartupRestorer(SessionPersistence sessionPersistence, SessionManager sessionManager,
                           WorkspaceManager workspaceManager, AdapterManager adapterManager,
                           AppConfigStore configStore) {
        this.sessionPersistence = sessionPersistence;
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
        this.adapterManager = adapterManager;
        this.configStore = configStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        restoreSessions();
        restoreAdapters();
    }

    /**
     * 恢复会话与工作区
     */
    private void restoreSessions() {
        var sessions = sessionPersistence.loadAllSessions();
        for (SessionManager.Session session : sessions) {
            // 重新注册会话绑定的工作区
            if (session.workspaceId() != null && workspaceManager.getWorkspace(session.workspaceId()).isEmpty()) {
                workspaceManager.registerWorkspace(session.workspaceId());
            }
            sessionManager.restoreSession(session.sessionId(), session.workspaceId(),
                session.mode(), session.createdAt(), session.name());
        }
        log.info("已恢复 {} 个会话", sessions.size());
    }

    /**
     * 恢复适配器配置
     */
    private void restoreAdapters() {
        applyAdapterConfig(ModelAdapter.AdapterType.OPENAI_COMPATIBLE, "openai");
        applyAdapterConfig(ModelAdapter.AdapterType.ANTHROPIC, "anthropic");

        String activeAdapter = configStore.get("activeAdapter", null);
        if (activeAdapter != null) {
            try {
                adapterManager.restoreActiveAdapter(ModelAdapter.AdapterType.valueOf(activeAdapter));
            } catch (IllegalArgumentException e) {
                log.warn("无法识别的激活适配器类型: {}", activeAdapter);
            }
        }
    }

    private void applyAdapterConfig(ModelAdapter.AdapterType type, String key) {
        Map<String, Object> saved = configStore.getMap(key);
        String baseUrl = saved.get("baseUrl") instanceof String s ? s : null;
        String apiKey = saved.get("apiKey") instanceof String s ? s : null;

        // 盒子出厂零配置：适配器级配置缺失时，回落到应用级默认（AppConfigStore
        // 已把 baseUrl 强制指向本地模型运行时），避免首次开机适配器不可用。
        // 仅对OpenAI兼容协议生效：本地运行时只说该协议。
        if ((baseUrl == null || baseUrl.isBlank())
                && type == ModelAdapter.AdapterType.OPENAI_COMPATIBLE) {
            String fallback = configStore.get("baseUrl", null);
            if (fallback instanceof String s && !s.isBlank()) {
                baseUrl = s;
                log.info("适配器 {} 未保存端点，回落到盒子默认本地端点: {}", type, baseUrl);
            }
        }

        if (baseUrl == null && apiKey == null) {
            return;
        }
        final String resolvedBaseUrl = baseUrl != null ? baseUrl : "";
        final String resolvedApiKey = apiKey != null ? apiKey : "";
        adapterManager.getAdapter(type).ifPresent(adapter -> {
            // 本地运行时不需要API-Key：不写入空白键，避免覆盖适配器内部状态
            Map<String, Object> cfg = new java.util.HashMap<>();
            cfg.put("baseUrl", resolvedBaseUrl);
            if (!resolvedApiKey.isBlank()) {
                cfg.put("apiKey", resolvedApiKey);
            }
            adapter.updateConfig(cfg);
            log.info("适配器配置已恢复: {} (baseUrl={})", type, resolvedBaseUrl);
        });
    }
}
