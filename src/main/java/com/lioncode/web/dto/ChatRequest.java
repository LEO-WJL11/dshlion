package com.lioncode.web.dto;

/**
 * 聊天请求DTO
 */
public record ChatRequest(
    String sessionId,
    String message,
    String model,
    String thinkingLevel,
    Boolean isSteer
) {}
