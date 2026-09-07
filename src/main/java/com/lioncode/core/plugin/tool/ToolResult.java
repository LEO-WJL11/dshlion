package com.lioncode.core.plugin.tool;

import java.util.Map;

/**
 * 工具执行结果
 */
public record ToolResult(
    /** 是否执行成功 */
    boolean success,
    /** 结果内容 */
    String content,
    /** 错误信息（失败时） */
    String error,
    /** 额外元数据 */
    Map<String, Object> metadata
) {
    /**
     * 创建成功结果
     */
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, Map.of());
    }

    /**
     * 创建成功结果（带元数据） */
    public static ToolResult success(String content, Map<String, Object> metadata) {
        return new ToolResult(true, content, null, metadata);
    }

    /**
     * 创建失败结果
     */
    public static ToolResult error(String error) {
        return new ToolResult(false, null, error, Map.of());
    }
}
