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
import java.util.Map;

/**
 * 会话管理控制器
 * 
 * 支持会话的创建、销毁、持久化恢复，以及历史消息查询和运行时模式切换。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    /** 这个类原来没有 logger，清空对话要记日志，补上 */
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(SessionController.class);

    private final SessionManager sessionManager;
    private final WorkspaceManager workspaceManager;
    private final SessionPersistence sessionPersistence;
    private final ConversationHistory conversationHistory;
    /** 会话被销毁时顺手清掉它"钉住"的技能，避免长时间运行攒一堆死会话的绑定关系 */
    private final com.lioncode.core.plugin.skill.SkillRepository skillRepository;

    public SessionController(SessionManager sessionManager, WorkspaceManager workspaceManager,
                             SessionPersistence sessionPersistence, ConversationHistory conversationHistory,
                             com.lioncode.core.plugin.skill.SkillRepository skillRepository) {
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
        this.sessionPersistence = sessionPersistence;
        this.conversationHistory = conversationHistory;
        this.skillRepository = skillRepository;
    }

    /**
     * 获取所有会话
     */
    @GetMapping
    public ApiResponse<List<SessionDto>> getAllSessions() {
        List<SessionDto> dtos = sessionManager.getAllSessions().stream()
            .map(s -> new SessionDto(s.sessionId(), s.workspaceId(),
                sessionManager.getEffectiveMode(s.sessionId()).getCode(), s.createdAt(), null, s.name()))
            .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * 创建新会话
     *
     * 注意：这里**不生成**会话名。名字等用户在这个会话里发出第一条消息后，
     * 由 SessionTitleService 拿这条消息的内容让模型生成（见 ChatController）。
     */
    @PostMapping
    public ApiResponse<SessionDto> createSession(@RequestBody CreateSessionRequest request) {
        try {
            // 现在只有标准和极简两种；PTC/创造会被归一成标准（老前端不会报错），
            // 不认识的名字会抛 IllegalArgumentException —— 以前这里只 catch 了
            // IllegalStateException，填错名字直接 500。
            var mode = AgentMode.fromName(request.mode());
            var session = sessionManager.createSession(request.workspaceId(), mode);
            // 持久化会话元数据，重启后自动恢复
            sessionPersistence.saveSession(session);
            SessionDto dto = new SessionDto(session.sessionId(), session.workspaceId(),
                session.mode().getCode(), session.createdAt(), null, session.name());
            return ApiResponse.ok("会话创建成功", dto);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的工作模式: " + request.mode() + "（只有 STANDARD / MINIMAL）");
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 重命名会话（用户手动修改；模型自动命名也走 SessionManager 那条路）
     */
    @PutMapping("/{sessionId}/name")
    public ApiResponse<String> renameSession(@PathVariable("sessionId") String sessionId,
                                             @RequestBody RenameSessionRequest request) {
        if (sessionManager.getSession(sessionId).isEmpty()) {
            return ApiResponse.error("会话不存在: " + sessionId);
        }
        boolean ok = sessionManager.renameSession(sessionId, request.name());
        if (!ok) {
            return ApiResponse.error("重命名失败");
        }
        return ApiResponse.ok("已重命名",
            sessionManager.getSession(sessionId).map(SessionManager.Session::name).orElse(null));
    }

    /**
     * 给这个对话换工作区（界面上对话列表里每个对话都能选）。
     *
     * <p>只改绑定关系；历史消息不动（里面的路径是绝对路径）。
     * 之后这个对话里的相对路径会基于新工作区解析。
     */
    @PutMapping("/{sessionId}/workspace")
    public ApiResponse<SessionDto> moveSessionToWorkspace(@PathVariable("sessionId") String sessionId,
                                                          @RequestBody MoveWorkspaceRequest request) {
        if (sessionManager.getSession(sessionId).isEmpty()) {
            return ApiResponse.error("会话不存在: " + sessionId);
        }
        if (request == null || request.workspaceId() == null || request.workspaceId().isBlank()) {
            return ApiResponse.error("缺少 workspaceId");
        }
        if (workspaceManager.getWorkspace(request.workspaceId()).isEmpty()) {
            return ApiResponse.error("工作区不存在: " + request.workspaceId());
        }
        boolean ok = sessionManager.moveSessionToWorkspace(sessionId, request.workspaceId());
        if (!ok) {
            return ApiResponse.error("换工作区失败");
        }
        return sessionManager.getSession(sessionId)
            .map(s -> ApiResponse.ok("已换到新工作区", new SessionDto(s.sessionId(), s.workspaceId(),
                sessionManager.getEffectiveMode(s.sessionId()).getCode(), s.createdAt(), null, s.name())))
            .orElseGet(() -> ApiResponse.error("会话不存在"));
    }

    /** 换工作区请求体 */
    public record MoveWorkspaceRequest(String workspaceId) {}

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
        skillRepository.clearSession(sessionId);
        return ApiResponse.ok("会话已销毁", null);
    }

    /**
     * 清空所有对话（界面上那个「清空」按钮）。
     *
     * <p>逐个走和单删一样的清理（内存会话 + 会话元数据 + 对话历史 + 磁盘上的历史文件），
     * 一个失败不影响其余 —— 最后返回实际删掉的数量，界面据此提示。
     * 工作区本身不会被删，只是里面的对话没了。
     */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> destroyAllSessions() {
        List<String> ids = sessionManager.getAllSessions().stream()
            .map(SessionManager.Session::sessionId).toList();
        int deleted = 0;
        int failed = 0;
        for (String sid : ids) {
            try {
                sessionManager.destroySession(sid);
                sessionPersistence.deleteSession(sid);
                conversationHistory.clearHistory(sid);
                conversationHistory.deleteFromDisk(sid);
                skillRepository.clearSession(sid);
                deleted++;
            } catch (Exception e) {
                failed++;
                log.warn("清空对话时删除会话失败: {} - {}", sid, e.getMessage());
            }
        }
        log.info("用户清空所有对话: 共 {} 个，删除成功 {}，失败 {}", ids.size(), deleted, failed);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("total", ids.size());
        data.put("deleted", deleted);
        data.put("failed", failed);
        String msg = "已清空 " + deleted + " 个对话" + (failed > 0 ? "（" + failed + " 个没删掉）" : "");
        return ApiResponse.ok(msg, data);
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
            AgentMode mode = AgentMode.fromName(request.mode());
            if (sessionManager.updateMode(sessionId, mode)) {
                // 用新模式重建元数据并持久化
                sessionManager.getSession(sessionId).ifPresent(s ->
                    sessionPersistence.saveSession(new SessionManager.Session(
                        s.sessionId(), s.workspaceId(), mode, s.createdAt(), s.name())));
                return ApiResponse.ok("模式已切换", mode.getCode());
            }
            return ApiResponse.error("模式切换失败");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的工作模式: " + request.mode() + "（只有 STANDARD / MINIMAL）");
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

    /**
     * 重命名会话请求
     */
    public record RenameSessionRequest(String name) {}
}
