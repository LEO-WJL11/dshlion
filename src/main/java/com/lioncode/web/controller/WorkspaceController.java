package com.lioncode.web.controller;

import com.lioncode.core.workspace.WorkspaceManager;
import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作区管理控制器
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceManager workspaceManager;

    public WorkspaceController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 注册工作区
     */
    @PostMapping
    public ApiResponse<WorkspaceManager.Workspace> registerWorkspace(@RequestBody RegisterRequest request) {
        var workspace = workspaceManager.registerWorkspace(request.path());
        return ApiResponse.ok("工作区注册成功", workspace);
    }

    /**
     * 获取所有已注册工作区
     */
    @GetMapping
    public ApiResponse<List<WorkspaceManager.Workspace>> getAllWorkspaces() {
        return ApiResponse.ok(workspaceManager.getAllWorkspaces());
    }

    /**
     * 获取默认工作区（用户主目录下的Desktop目录，由后端动态计算）
     * 前端自动初始化使用，避免硬编码用户路径
     */
    @GetMapping("/default")
    public ApiResponse<WorkspaceManager.Workspace> getDefaultWorkspace() {
        String desktop = Path.of(System.getProperty("user.home"), "Desktop").toString();
        var workspace = workspaceManager.registerWorkspace(desktop);
        return ApiResponse.ok("ok", workspace);
    }

    /**
     * 获取常用路径列表（基于用户主目录动态计算）
     * 前端工作区选择弹窗使用，避免硬编码用户路径
     */
    @GetMapping("/common")
    public ApiResponse<List<CommonPath>> getCommonPaths() {
        String home = System.getProperty("user.home");
        List<CommonPath> paths = List.of(
            new CommonPath("🖥️", "桌面", Path.of(home, "Desktop").toString()),
            new CommonPath("📄", "文档", Path.of(home, "Documents").toString()),
            new CommonPath("📥", "下载", Path.of(home, "Downloads").toString()),
            new CommonPath("🏠", "用户目录", home)
        );
        return ApiResponse.ok("ok", paths);
    }

    /**
     * 更新工作区权限等级（READ_ONLY / WORKSPACE_WRITE / FULL_ACCESS）
     * 权限在AgentLoop执行工具时强制检查。
     * 工作区ID是含反斜杠的绝对路径，不适合放URL路径，故用body传参。
     */
    @PostMapping("/permission")
    public ApiResponse<WorkspaceManager.Workspace> setPermission(
            @RequestBody PermissionRequest request) {
        try {
            WorkspaceManager.WorkspacePermission permission =
                WorkspaceManager.WorkspacePermission.valueOf(request.permission());
            if (workspaceManager.setPermission(request.id(), permission)) {
                return ApiResponse.ok("权限已更新", workspaceManager.getWorkspace(request.id()).orElse(null));
            }
            return ApiResponse.error("工作区不存在: " + request.id());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的权限等级: " + request.permission());
        }
    }

    /**
     * 注册请求
     */
    public record RegisterRequest(String path) {}

    /**
     * 权限更新请求
     */
    public record PermissionRequest(String id, String permission) {}

    /**
     * 常用路径
     */
    public record CommonPath(String icon, String name, String path) {}
}
