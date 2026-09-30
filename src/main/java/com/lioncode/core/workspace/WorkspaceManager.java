package com.lioncode.core.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区管理器
 * 
 * 三级工作区权限：只读 / 工作区写 / 全部权限。
 * 强制绑定工作区：未选择工作区，不能创建和使用对话会话。
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** 工作区映射：ID -> 工作区信息 */
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();

    /**
     * 注册工作区（默认授予"工作区写"权限）
     *
     * <p>已经注册过的工作区**不覆盖它已有的权限等级**：
     * 前端每次启动都会调 {@code GET /api/workspaces/default} 重新注册一遍默认工作区，
     * 以前那会把用户手动设成"只读/全部权限"的工作区悄悄改回"工作区写"。
     *
     * <p>【null / 空白 / 非法路径】必须在这里就拦下并抛 IllegalArgumentException：
     * 以前直接 {@code Path.of(path)} —— null 抛 NPE、非法字符抛 InvalidPathException，
     * 两者都不是 IllegalArgumentException，控制器拦不住，用户看到的是 500；
     * 而 {@code Path.of("")} 等于**当前进程工作目录**，会把用户根本没选过的目录静默注册成工作区。
     */
    public Workspace registerWorkspace(String path) {
        String id = workspaceIdOf(path);
        Workspace existing = workspaces.get(id);
        if (existing != null) {
            return existing;
        }
        return registerWorkspace(path, WorkspacePermission.WORKSPACE_WRITE);
    }

    /**
     * 注册工作区并指定权限等级
     *
     * @throws IllegalArgumentException 路径为空或不是合法路径
     */
    public Workspace registerWorkspace(String path, WorkspacePermission permission) {
        String id = workspaceIdOf(path);
        Workspace workspace = new Workspace(id, path, permission);
        workspaces.put(id, workspace);
        log.info("工作区已注册: {} (权限: {})", path, permission);
        return workspace;
    }

    /** 路径校验 + 归一化成工作区 ID（绝对路径） */
    private static String workspaceIdOf(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("工作区路径不能为空");
        }
        try {
            return Path.of(path).toAbsolutePath().toString();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("工作区路径非法: " + path);
        }
    }

    /**
     * 获取工作区
     */
    public Optional<Workspace> getWorkspace(String id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    /**
     * 更新工作区权限等级（只读/工作区写/全部权限）
     * 权限在AgentLoop.executeTool中强制执行。
     */
    public boolean setPermission(String id, WorkspacePermission permission) {
        Workspace existing = workspaces.get(id);
        if (existing == null) {
            log.warn("更新权限失败，工作区不存在: {}", id);
            return false;
        }
        workspaces.put(id, new Workspace(existing.id(), existing.path(), permission));
        log.info("工作区权限已更新: {} -> {}", id, permission);
        return true;
    }

    /**
     * 获取所有已注册工作区
     */
    public List<Workspace> getAllWorkspaces() {
        return new java.util.ArrayList<>(workspaces.values());
    }

    /**
     * 工作区数据类
     */
    public record Workspace(
        String id,
        String path,
        WorkspacePermission permission
    ) {}

    /**
     * 工作区权限枚举
     */
    public enum WorkspacePermission {
        /** 只读 */
        READ_ONLY,
        /** 工作区写 */
        WORKSPACE_WRITE,
        /** 全部权限 */
        FULL_ACCESS
    }
}
