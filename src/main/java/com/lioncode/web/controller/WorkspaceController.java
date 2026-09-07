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
     * 注册请求
     */
    public record RegisterRequest(String path) {}

    /**
     * 常用路径
     */
    public record CommonPath(String icon, String name, String path) {}
}
