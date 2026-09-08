package com.lioncode.web.controller;

import com.lioncode.core.agent.AgentMode;
import com.lioncode.core.session.ConversationHistory;
import com.lioncode.core.session.ConversationMessage;
import com.lioncode.core.session.SessionManager;
import com.lioncode.core.session.SessionPersistence;
import com.lioncode.core.workspace.WorkspaceManager;
import com.lioncode.web.dto.ApiResponse;
import com.lioncode.web.dto.SessionDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 * 
 * 支持会话的创建、销毁、持久化恢复，以及历史消息查询和运行时模式切换。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionManager sessionManager;
    private final WorkspaceManager workspaceManager;
    private final SessionPersistence sessionPersistence;
    private final ConversationHistory conversationHistory;

    public SessionController(SessionManager sessionManager, WorkspaceManager workspaceManager,
                             SessionPersistence sessionPersistence, ConversationHistory conversationHistory) {
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
        this.sessionPersistence = sessionPersistence;
        this.conversationHistory = conversationHistory;
    }

    /**
     * 获取所有会话
     */
    @GetMapping
    public ApiResponse<List<SessionDto>> getAllSessions() {
        List<SessionDto> dtos = sessionManager.getAllSessions().stream()
            .map(s -> new SessionDto(s.sessionId(), s.workspaceId(),
                sessionManager.getEffectiveMode(s.sessionId()).getCode(), s.createdAt(), null))
            .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * 创建新会话
     */
    @PostMapping
    public ApiResponse<SessionDto> createSession(@RequestBody CreateSessionRequest request) {
        try {
            var mode = AgentMode.valueOf(request.mode());
            var session = sessionManager.createSession(request.workspaceId(), mode);
            // 持久化会话元数据，重启后自动恢复
            sessionPersistence.saveSession(session);
            SessionDto dto = new SessionDto(session.sessionId(), session.workspaceId(),
                session.mode().getCode(), session.createdAt(), null);
            return ApiResponse.ok("会话创建成功", dto);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 销毁会话
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> destroySession(@PathVariable("sessionId") String sessionId) {
        sessionManager.destroySession(sessionId);
        // 同步清理持久化数据
        sessionPersistence.deleteSession(sessionId);
        conversationHistory.clearHistory(sessionId);
        conversationHistory.deleteFromDisk(sessionId);
        return ApiResponse.ok("会话已销毁", null);
    }

    /**
     * 运行时切换会话工作模式（立即生效）
     */
    @PostMapping("/{sessionId}/mode")
    public ApiResponse<String> updateMode(@PathVariable("sessionId") String sessionId,
                                          @RequestBody UpdateModeRequest request) {
        if (sessionManager.getSession(sessionId).isEmpty()) {
            return ApiResponse.error("会话不存在: " + sessionId);
        }
        try {
            AgentMode mode = AgentMode.valueOf(request.mode());
            if (sessionManager.updateMode(sessionId, mode)) {
                // 用新模式重建元数据并持久化
                sessionManager.getSession(sessionId).ifPresent(s ->
                    sessionPersistence.saveSession(new SessionManager.Session(
                        s.sessionId(), s.workspaceId(), mode, s.createdAt())));
                return ApiResponse.ok("模式已切换", mode.getCode());
            }
            return ApiResponse.error("模式切换失败");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的工作模式: " + request.mode());
        }
    }

    /**
     * 获取会话的完整对话历史（用于前端切换会话时恢复消息展示）
     */
    @GetMapping("/{sessionId}/history")
    public ApiResponse<List<ConversationMessage>> getHistory(@PathVariable("sessionId") String sessionId) {
        if (sessionManager.getSession(sessionId).isEmpty()) {
            return ApiResponse.error("会话不存在: " + sessionId);
        }
        return ApiResponse.ok(conversationHistory.getHistory(sessionId));
    }

    /**
     * 创建会话请求
     */
    public record CreateSessionRequest(String workspaceId, String mode) {}

    /**
     * 切换模式请求
     */
    public record UpdateModeRequest(String mode) {}
}
