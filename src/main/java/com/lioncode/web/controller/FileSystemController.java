package com.lioncode.web.controller;

import com.lioncode.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 文件系统浏览控制器
 * 
 * 提供目录浏览功能，用于前端文件选择对话框
 */
@RestController
@RequestMapping("/api/filesystem")
public class FileSystemController {

    /**
     * 浏览目录内容
     * 返回文件和子目录列表
     */
    @GetMapping("/browse")
    public ApiResponse<?> browse(
            @RequestParam(value = "path", defaultValue = "") String path,
            @RequestParam(value = "showFiles", defaultValue = "false") boolean showFiles) {
        
        try {
            // 如果路径为空，返回驱动器列表（Windows）
            if (path == null || path.isBlank()) {
                return ApiResponse.ok(getDrives());
            }

            Path dirPath = Path.of(path);
            if (!Files.exists(dirPath)) {
                return ApiResponse.error("路径不存在: " + path);
            }
            if (!Files.isDirectory(dirPath)) {
                return ApiResponse.error("不是目录: " + path);
            }

            File dir = dirPath.toFile();
            File[] files = dir.listFiles();
            
            List<FileEntry> entries = Arrays.stream(files != null ? files : new File[0])
                .filter(f -> showFiles || f.isDirectory())
                .sorted((a, b) -> {
                    // 目录排在前面
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                })
                .map(f -> new FileEntry(
                    f.getName(),
                    f.getAbsolutePath(),
                    f.isDirectory(),
                    f.isDirectory() ? null : f.length(),
                    f.lastModified()
                ))
                .toList();

            // 获取父目录
            String parentPath = dirPath.getParent() != null ? 
                dirPath.getParent().toAbsolutePath().toString() : null;

            return ApiResponse.ok(new BrowseResult(
                dirPath.toAbsolutePath().toString(),
                parentPath,
                entries
            ));

        } catch (SecurityException e) {
            return ApiResponse.error("权限不足: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("浏览失败: " + e.getMessage());
        }
    }

    /**
     * 获取Windows驱动器列表
     */
    @GetMapping("/drives")
    public ApiResponse<List<DriveInfo>> getDrivesList() {
        return ApiResponse.ok(getDrives());
    }

    /**
     * 验证路径是否存在
     */
    @GetMapping("/validate")
    public ApiResponse<ValidateResult> validatePath(@RequestParam("path") String path) {
        try {
            Path p = Path.of(path);
            boolean exists = Files.exists(p);
            boolean isDir = Files.isDirectory(p);
            boolean isFile = Files.isRegularFile(p);
            boolean readable = Files.isReadable(p);
            boolean writable = Files.isWritable(p);

            return ApiResponse.ok(new ValidateResult(exists, isDir, isFile, readable, writable));
        } catch (Exception e) {
            return ApiResponse.ok(new ValidateResult(false, false, false, false, false));
        }
    }

    /**
     * 创建目录
     */
    @PostMapping("/mkdir")
    public ApiResponse<Void> createDirectory(@RequestBody Map<String, String> request) {
        try {
            String path = request.get("path");
            if (path == null || path.isBlank()) {
                return ApiResponse.error("路径不能为空");
            }
            Files.createDirectories(Path.of(path));
            return ApiResponse.ok("目录已创建", null);
        } catch (Exception e) {
            return ApiResponse.error("创建目录失败: " + e.getMessage());
        }
    }

    /**
     * 获取驱动器列表
     */
    private List<DriveInfo> getDrives() {
        return Arrays.stream(File.listRoots())
            .map(root -> {
                long totalSpace = root.getTotalSpace();
                long freeSpace = root.getFreeSpace();
                String label = root.getAbsolutePath();
                
                // Windows驱动器标签
                if (label.endsWith(":\\")) {
                    label = label.substring(0, 2);
                }
                
                return new DriveInfo(
                    label,
                    root.getAbsolutePath(),
                    totalSpace,
                    freeSpace,
                    root.canRead()
                );
            })
            .filter(d -> d.readable())
            .toList();
    }

    /**
     * 文件条目
     */
    public record FileEntry(
        String name,
        String path,
        boolean isDirectory,
        Long size,
        long lastModified
    ) {}

    /**
     * 浏览结果
     */
    public record BrowseResult(
        String currentPath,
        String parentPath,
        List<FileEntry> entries
    ) {}

    /**
     * 驱动器信息
     */
    public record DriveInfo(
        String label,
        String path,
        long totalSpace,
        long freeSpace,
        boolean readable
    ) {}

    /**
     * 路径验证结果
     */
    public record ValidateResult(
        boolean exists,
        boolean isDirectory,
        boolean isFile,
        boolean readable,
        boolean writable
    ) {}
}
