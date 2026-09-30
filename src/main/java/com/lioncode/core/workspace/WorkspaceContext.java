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
     * - 相对路径：拼接到工作区根目录下，**并且同样做越界校验**
     * - 无工作区上下文：原样返回（未绑定工作区时不限制）
     *
     * 【相对路径为什么也要校验】以前只有 isAbsolute() 分支做校验，相对路径直接
     * {@code Path.of(ws).resolve(p).toString()} 就返回了 —— resolve() **不做规范化**，
     * 于是 {@code path="..\\..\\..\\Windows\\System32\\drivers\\etc\\hosts"} 会原样拼成
     * {@code C:\ws\..\..\..\Windows\...\hosts} 交给工具，工具再 toAbsolutePath/normalize 之后
     * 就落在工作区外面了：沙箱形同虚设（写文件、读文件、删除全都逃得出去）。
     * 现在统一 normalize 之后再判断是否仍在工作区内。
     */
    public static String resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        Path p = Path.of(rawPath);
        String ws = WORKSPACE.get();
        boolean strict = strictMode && ws != null && !ws.isBlank();

        if (p.isAbsolute()) {
            if (strict) {
                Path wsPath = Path.of(ws).toAbsolutePath().normalize();
                Path abs = p.toAbsolutePath().normalize();
                checkInside(abs, wsPath, rawPath, ws);
                // 符号链接/junction 也要拦：normalize() 只处理 ".."，不会解析链接
                checkRealPathInside(abs, wsPath, rawPath, ws);
            }
            return rawPath;
        }
        if (ws == null || ws.isBlank()) {
            return rawPath;
        }
        Path wsPath = Path.of(ws).toAbsolutePath().normalize();
        Path resolved = wsPath.resolve(p).normalize();
        if (strict) {
            // 校验放在归一化之后：先判断再归一化是典型漏洞（校验用的是没归一化的串）
            checkInside(resolved, wsPath, rawPath, ws);
        }
        return resolved.toString();
    }

    /** 归一化后的包含性判断 */
    private static void checkInside(Path candidate, Path wsPath, String rawPath, String ws) {
        if (!candidate.startsWith(wsPath)) {
            throw new IllegalStateException(
                "路径在工作区之外，已阻止访问: " + rawPath + "（当前工作区: " + ws + "）");
        }
    }

    /**
     * 解析真实路径（跟随符号链接）后再判一次。
     * 目标不存在时（新建文件的场景）用父目录判断，父目录也不存在就跳过——
     * 这种情况由 checkInside 的字符串判断兜底。
     */
    private static void checkRealPathInside(Path abs, Path wsPath, String rawPath, String ws) {
        try {
            Path real = abs;
            if (!java.nio.file.Files.exists(real)) {
                Path parent = real.getParent();
                if (parent == null) {
                    return;
                }
                real = parent;
            }
            Path realPath = real.toRealPath();
            Path realWs = java.nio.file.Files.exists(wsPath) ? wsPath.toRealPath() : wsPath;
            checkInside(realPath, realWs, rawPath, ws);
        } catch (IllegalStateException e) {
            throw e;
        } catch (java.io.IOException e) {
            // 拿不到真实路径（权限等）：退回字符串判断的结果，不额外放行也不额外拦截
        }
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
