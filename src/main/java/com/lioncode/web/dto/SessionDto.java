package com.lioncode.web.dto;

import java.time.Instant;

/**
 * 会话DTO
 *
 * name：会话标题。新建时为 null，界面上先显示ID前缀；
 *       用户在这个会话里发出第一条消息后由模型生成，也可以手动改。
 */
public record SessionDto(
    String sessionId,
    String workspaceId,
    String mode,
    Instant createdAt,
    String adapterType,
    String name
) {}
