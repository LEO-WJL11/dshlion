package com.lioncode.core.agent.change;

import java.util.List;

/**
 * 一条待人工审核的改动。
 *
 * @param id         改动编号（用户/界面用它来通过或打回）
 * @param sessionId  属于哪个会话（审完之后要把结论告诉这个会话的模型）
 * @param toolName   哪个工具发起的（write_file / modify_file / delete_file …）
 * @param path       目标文件（绝对路径）
 * @param existed    改之前这个文件在不在
 * @param oldContent 改之前的内容（新文件为 null）
 * @param newContent 改之后的内容（删除操作为 null）
 * @param diff       给人看的差异文本
 * @param createdAt  提交时间（epoch millis）
 * @param status     PENDING / APPROVED / REJECTED
 * @param reason     打回理由（通过时为空）
 */
public record PendingChange(
    String id,
    String sessionId,
    String toolName,
    String path,
    boolean existed,
    String oldContent,
    String newContent,
    String diff,
    long createdAt,
    String status,
    String reason
) {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public PendingChange withStatus(String newStatus, String newReason) {
        return new PendingChange(id, sessionId, toolName, path, existed, oldContent, newContent,
            diff, createdAt, newStatus, newReason);
    }

    /** 给界面看的摘要（不返回全文，几十 KB 的内容不该塞进列表接口） */
    public java.util.Map<String, Object> summary() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("sessionId", sessionId);
        m.put("toolName", toolName);
        m.put("path", path);
        m.put("existed", existed);
        m.put("status", status);
        m.put("reason", reason);
        m.put("createdAt", createdAt);
        m.put("diff", diff);
        m.put("addedLines", countLines(newContent));
        m.put("removedLines", existed && oldContent != null ? countLines(oldContent) : 0);
        return m;
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 待审列表（按时间正序） */
    public static List<PendingChange> none() {
        return List.of();
    }
}
