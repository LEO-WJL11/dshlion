package com.lioncode.web.controller;

import com.lioncode.core.session.SessionManager;
import com.lioncode.core.workspace.WorkspaceManager;
import com.lioncode.web.dto.ApiResponse;
import com.lioncode.web.dto.SessionDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionManager sessionManager;
    private final WorkspaceManager workspaceManager;

    public SessionController(SessionManager sessionManager, WorkspaceManager workspaceManager) {
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
    }

    /**
     * 获取所有会话
     */
    @GetMapping
    public ApiResponse<List<SessionDto>> getAllSessions() {
        List<SessionDto> dtos = sessionManager.getAllSessions().stream()
            .map(s -> new SessionDto(s.sessionId(), s.workspaceId(), 
                s.mode().getCode(), s.createdAt(), null))
            .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * 创建新会话
     */
    @PostMapping
    public ApiResponse<SessionDto> createSession(@RequestBody CreateSessionRequest request) {
        try {
            var mode = com.lioncode.core.agent.AgentMode.valueOf(request.mode());
            var session = sessionManager.createSession(request.workspaceId(), mode);
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
        return ApiResponse.ok("会话已销毁", null);
    }

    /**
     * 创建会话请求
     */
    public record CreateSessionRequest(String workspaceId, String mode) {}
}
