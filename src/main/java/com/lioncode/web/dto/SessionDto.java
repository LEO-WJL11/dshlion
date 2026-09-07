package com.lioncode.web.dto;

import java.time.Instant;

/**
 * 会话DTO
 */
public record SessionDto(
    String sessionId,
    String workspaceId,
    String mode,
    Instant createdAt,
    String adapterType
) {}
