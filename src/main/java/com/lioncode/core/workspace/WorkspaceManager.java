package com.lioncode.core.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
     * 注册工作区
     */
    public Workspace registerWorkspace(String path) {
        Path workspacePath = Path.of(path);
        String id = workspacePath.toAbsolutePath().toString();
        Workspace workspace = new Workspace(id, path, WorkspacePermission.WORKSPACE_WRITE);
        workspaces.put(id, workspace);
        log.info("工作区已注册: {}", path);
        return workspace;
    }

    /**
     * 获取工作区
     */
    public Optional<Workspace> getWorkspace(String id) {
        return Optional.ofNullable(workspaces.get(id));
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
