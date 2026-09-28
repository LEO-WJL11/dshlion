package com.lioncode.web.controller;

import com.lioncode.core.agent.AgentControlManager;
import com.lioncode.core.question.UserQuestionService;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

/**
 * Agent运行控制接口
 * 
 * 前端在任务运行期间调用：
 * - POST /api/chat/control/pause    暂停（下一轮工具调用前生效）
 * - POST /api/chat/control/resume   继续
 * - POST /api/chat/control/stop     停止（下一轮工具调用前生效）
 * - GET  /api/chat/control/status   查询当前状态
 */
@RestController
@RequestMapping("/api/chat/control")
public class AgentControlController {

    private final AgentControlManager agentControl;
    private final UserQuestionService questionService;

    public AgentControlController(AgentControlManager agentControl,
                                  UserQuestionService questionService) {
        this.agentControl = agentControl;
        this.questionService = questionService;
    }

    /**
     * 控制请求
     */
    public record ControlRequest(String sessionId) {}

    /**
     * 暂停
     */
    @PostMapping("/pause")
    public ApiResponse<String> pause(@RequestBody ControlRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error("sessionId不能为空");
        }
        agentControl.pause(request.sessionId());
        return ApiResponse.ok("已暂停", null);
    }

    /**
     * 继续
     */
    @PostMapping("/resume")
    public ApiResponse<String> resume(@RequestBody ControlRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error("sessionId不能为空");
        }
        agentControl.resume(request.sessionId());
        return ApiResponse.ok("已继续", null);
    }

    /**
     * 停止
     *
     * 除了给 AgentControlManager 打停止标记，还要**取消该会话上等待中的提问**：
     * ask_user 工具是阻塞等待用户回答的，不主动放行的话，
     * 用户点了停止、那个 worker 线程还会一直挂在那儿等满 5 分钟才回来。
     */
    @PostMapping("/stop")
    public ApiResponse<String> stop(@RequestBody ControlRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error("sessionId不能为空");
        }
        agentControl.stop(request.sessionId());
        int cancelled = questionService.cancelSession(request.sessionId());
        return ApiResponse.ok(cancelled > 0
            ? "已请求停止（同时取消了 " + cancelled + " 个等待中的提问）"
            : "已请求停止", null);
    }

    /**
     * 查询当前状态
     */
    @GetMapping("/status")
    public ApiResponse<String> status(@RequestParam("sessionId") String sessionId) {
        return ApiResponse.ok("ok", agentControl.getState(sessionId).name());
    }
}
