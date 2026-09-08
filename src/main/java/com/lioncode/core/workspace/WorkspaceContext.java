package com.lioncode.core.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;

/**
 * 工具执行期间的工作区上下文（线程级）
 * 
 * AgentLoop在执行工具前设置当前会话绑定的工作区路径，
 * 各工具通过resolvePath将相对路径解析到工作区根目录下，
 * 从而实现"选择工作区 → 工具真正在该工作区内操作"。
 * 
 * 沙箱：严格模式下，绑定工作区后绝对路径必须位于工作区内，
 * 否则抛出IllegalStateException（工具层统一捕获并返回错误），
 * 防止工具越界访问工作区外的文件。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<String> WORKSPACE = new ThreadLocal<>();

    /** 严格沙箱开关（默认开启，可通过 lion.workspace.strict=false 关闭） */
    private static volatile boolean strictMode = true;

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
     * 设置严格沙箱开关（由Spring配置注入）
     */
    public static void setStrictMode(boolean strict) {
        strictMode = strict;
    }

    /**
     * 解析路径：
     * - 绝对路径：严格模式下校验必须位于工作区内（越界抛出IllegalStateException）
     * - 相对路径：拼接到工作区根目录下
     * - 无工作区上下文：原样返回（未绑定工作区时不限制）
     */
    public static String resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        Path p = Path.of(rawPath);
        String ws = WORKSPACE.get();
        if (p.isAbsolute()) {
            if (strictMode && ws != null && !ws.isBlank()) {
                Path wsPath = Path.of(ws).toAbsolutePath().normalize();
                Path abs = p.toAbsolutePath().normalize();
                if (!abs.startsWith(wsPath)) {
                    throw new IllegalStateException(
                        "路径在工作区之外，已阻止访问: " + rawPath + "（当前工作区: " + ws + "）");
                }
            }
            return rawPath;
        }
        if (ws == null || ws.isBlank()) {
            return rawPath;
        }
        return Path.of(ws).resolve(p).toString();
    }

    /**
     * 工作区配置注入组件：把 lion.workspace.strict 配置同步到WorkspaceContext
     */
    @Component
    public static class WorkspaceContextConfig {

        @Value("${lion.workspace.strict:true}")
        private boolean strict;

        @PostConstruct
        public void init() {
            WorkspaceContext.setStrictMode(strict);
        }
    }
}
