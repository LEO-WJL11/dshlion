package com.lioncode.core.context;

import com.lioncode.core.session.SessionManager;
import com.lioncode.core.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @ 引用要用的"根目录"解析：@file 到底相对谁找、能碰哪些文件。
 *
 * <p>解析顺序（和工具层 {@code WorkspaceContext.resolve} 保持一致，用户才不会觉得两套规则）：
 * <ol>
 *   <li>请求里显式给的 {@code root}（补全接口带过来的，用户在界面上选了目录）</li>
 *   <li>会话绑定的工作区（正常对话都走这条）</li>
 *   <li>都没有时退到进程工作目录（老会话、没绑定工作区的边缘情况）</li>
 * </ol>
 *
 * <p>【为什么要有越界检查】@file 是"把文件内容读出来发给模型"，如果不检查，
 * 用户（或模型自己补出来的路径）写 {@code @file:../../../../etc/passwd}、
 * {@code @file:C:\Windows\...} 就能把工作区外的文件读进上下文。
 * 工具层有同样的沙箱（WorkspaceContext 严格模式），这里必须对齐。</p>
 */
@Component
public class ContextRoots {

    private final SessionManager sessionManager;
    private final WorkspaceManager workspaceManager;

    public ContextRoots(SessionManager sessionManager, WorkspaceManager workspaceManager) {
        this.sessionManager = sessionManager;
        this.workspaceManager = workspaceManager;
    }

    /**
     * 解析根目录（绝对、规范化）。
     *
     * @param sessionId    会话 id，可为 null
     * @param explicitRoot 请求显式指定的根目录，可为空
     */
    public Path resolve(String sessionId, String explicitRoot) {
        if (explicitRoot != null && !explicitRoot.isBlank()) {
            Path p = Path.of(explicitRoot.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path ws = sessionWorkspace(sessionId);
        if (ws != null) {
            return ws;
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 会话绑定的工作区路径；没有返回 null。 */
    public Path sessionWorkspace(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionManager.getSession(sessionId)
                .flatMap(s -> workspaceManager.getWorkspace(s.workspaceId()))
                .map(ws -> Path.of(ws.path()).toAbsolutePath().normalize())
                .orElse(null);
        } catch (Exception e) {
            // 工作区 id 不是合法路径这类脏数据：不要让 @ 引用把整条消息弄挂
            return null;
        }
    }

    /**
     * 把 {@code @file:} 里的路径解析成根目录内的真实路径。
     *
     * @return 越界或非法时返回 null（调用方给一句"在工作区之外，已拒绝"）
     */
    public Path contain(Path root, String rawPath) {
        if (root == null || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(rawPath.trim());
            Path target = p.isAbsolute() ? p.toAbsolutePath().normalize() : root.resolve(p).normalize();
            return target.startsWith(root) ? target : null;
        } catch (Exception e) {
            return null;   // 路径里有非法字符（Windows 上很常见）
        }
    }

    /** 相对根目录的展示路径（统一用 / 分隔，前端 insert 直接用）。 */
    public String displayPath(Path root, Path target) {
        try {
            if (root != null && target.startsWith(root)) {
                return root.relativize(target).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // 跨盘符时 relativize 会抛异常，退回绝对路径
        }
        return target.toString().replace('\\', '/');
    }
}
