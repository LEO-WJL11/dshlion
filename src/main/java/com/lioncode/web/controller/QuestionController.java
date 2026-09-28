package com.lioncode.web.controller;

import com.lioncode.core.question.UserQuestionService;
import com.lioncode.core.session.SessionManager;
import com.lioncode.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向用户提问的接口（配合 ask_user 工具）
 *
 * 流程：
 *   模型调 ask_user → 后端登记一条待回答的问题并阻塞等待
 *   → 前端在处理过程中轮询 GET /api/questions/pending 看到问题、弹出输入框
 *   → 用户填完 POST /api/questions/answer → 后端放行，答案回到模型
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    private final UserQuestionService questionService;
    private final SessionManager sessionManager;

    public QuestionController(UserQuestionService questionService, SessionManager sessionManager) {
        this.questionService = questionService;
        this.sessionManager = sessionManager;
    }

    /**
     * 某会话当前有没有待回答的问题（前端每轮处理期间轮询这个）
     *
     * 没有待回答时 data 为 null——前端据此把输入框收起来。
     */
    @GetMapping("/pending")
    public ApiResponse<Map<String, Object>> pending(@RequestParam("sessionId") String sessionId) {
        if (sessionManager.getSession(sessionId).isEmpty()) {
            return ApiResponse.error("会话不存在: " + sessionId);
        }
        return ApiResponse.ok(
            questionService.pendingOf(sessionId).map(questionService::toMap).orElse(null));
    }

    /**
     * 提交回答
     */
    @PostMapping("/answer")
    public ApiResponse<Map<String, Object>> answer(@RequestBody AnswerRequest request) {
        if (request == null || request.questionId() == null || request.questionId().isBlank()) {
            return ApiResponse.error("缺少 questionId");
        }
        String answer = request.answer() == null ? "" : request.answer().trim();
        if (answer.isEmpty()) {
            return ApiResponse.error("回答不能为空");
        }
        boolean ok = questionService.answer(request.questionId(), answer);
        if (!ok) {
            return ApiResponse.error("这个问题已经超时或已被取消，请等模型下一步的反应");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("questionId", request.questionId());
        data.put("answer", answer);
        log.info("用户已回答提问 {}（{} 字）", request.questionId(), answer.length());
        return ApiResponse.ok("已回答", data);
    }

    /**
     * 待回答问题总数（诊断用）
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", questionService.pendingCount());
        return ApiResponse.ok(m);
    }

    /** 提交回答的请求体 */
    public record AnswerRequest(String questionId, String answer) {}
}
