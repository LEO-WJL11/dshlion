package com.lioncode.core.workspace;

import java.nio.file.Path;

/**
 * 工具执行期间的工作区上下文（线程级）
 * 
 * AgentLoop在执行工具前设置当前会话绑定的工作区路径，
 * 各工具通过resolvePath将相对路径解析到工作区根目录下，
 * 从而实现"选择工作区 → 工具真正在该工作区内操作"。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<String> WORKSPACE = new ThreadLocal<>();

    private WorkspaceContext() {}

    /**
     * 设置当前线程的工作区路径
     */
    public static void set(String workspacePath) {
        WORKSPACE.set(workspacePath);
    }

    /**
     * 获取当前线程的工作区路径（可能为null）
     */
    public static String get() {
        return WORKSPACE.get();
    }

    /**
     * 清除当前线程的工作区上下文
     */
    public static void clear() {
        WORKSPACE.remove();
    }

    /**
     * 解析路径：
     * - 绝对路径原样返回
     * - 相对路径拼接到工作区根目录下
     * - 无工作区上下文时原样返回
     */
    public static String resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        Path p = Path.of(rawPath);
        if (p.isAbsolute()) {
            return rawPath;
        }
        String ws = WORKSPACE.get();
        if (ws == null || ws.isBlank()) {
            return rawPath;
        }
        return Path.of(ws).resolve(p).toString();
    }
}
