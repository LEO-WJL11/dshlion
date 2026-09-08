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
 * 2. 恢复两个模型适配器的 baseUrl/apiKey 配置
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
                session.mode(), session.createdAt());
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
        if (saved.isEmpty()) {
            return;
        }
        adapterManager.getAdapter(type).ifPresent(adapter -> {
            String baseUrl = saved.get("baseUrl") instanceof String s ? s : null;
            String apiKey = saved.get("apiKey") instanceof String s ? s : null;
            if (baseUrl != null || apiKey != null) {
                adapter.updateConfig(Map.of(
                    "baseUrl", baseUrl != null ? baseUrl : "",
                    "apiKey", apiKey != null ? apiKey : ""
                ));
                log.info("适配器配置已恢复: {} (baseUrl={})", type, baseUrl);
            }
        });
    }
}
