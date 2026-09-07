package com.lioncode.web.controller;

import com.lioncode.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.*;

/**
 * Windows原生文件夹选择
 * 通过PowerShell调用Windows Shell的FolderPicker
 */
@RestController
@RequestMapping("/api/native-dialog")
public class NativeDialogController {

    private static final Logger log = LoggerFactory.getLogger(NativeDialogController.class);

    /**
     * 打开Windows原生文件夹选择对话框
     * 使用PowerShell的FolderBrowserDialog
     */
    @GetMapping("/select-directory")
    public ApiResponse<String> selectDirectory(
            @RequestParam(value = "initialPath", defaultValue = "") String initialPath) {
        try {
            String initial = initialPath.isEmpty() ? 
                System.getProperty("user.home") + "\\Desktop" : initialPath;

            // 用PowerShell调用Windows Forms的FolderBrowserDialog
            String psScript = String.format("""
                Add-Type -AssemblyName System.Windows.Forms
                $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
                $dialog.Description = '选择工作区目录'
                $dialog.SelectedPath = '%s'
                $dialog.ShowNewFolderButton = $true
                $result = $dialog.ShowDialog()
                if ($result -eq [System.Windows.Forms.DialogResult]::OK) {
                    Write-Output $dialog.SelectedPath
                }
                """, initial.replace("'", "''"));

            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", psScript
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a + b);
            }

            boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ApiResponse.error("对话框超时");
            }

            output = output.trim();
            if (output.isEmpty()) {
                return ApiResponse.error("用户取消选择");
            }

            log.info("用户选择目录: {}", output);
            return ApiResponse.ok("目录已选择", output);

        } catch (Exception e) {
            log.error("打开文件夹选择对话框失败", e);
            return ApiResponse.error("打开对话框失败: " + e.getMessage());
        }
    }

    /**
     * 获取系统常用目录
     */
    @GetMapping("/common-dirs")
    public ApiResponse<Map<String, String>> getCommonDirectories() {
        Map<String, String> dirs = new LinkedHashMap<>();
        String home = System.getProperty("user.home");
        dirs.put("桌面", home + File.separator + "Desktop");
        dirs.put("文档", home + File.separator + "Documents");
        dirs.put("下载", home + File.separator + "Downloads");
        dirs.put("图片", home + File.separator + "Pictures");
        dirs.put("用户目录", home);
        for (File root : File.listRoots()) {
            String label = root.getAbsolutePath();
            if (label.endsWith(":\\")) label = label.substring(0, 2);
            dirs.put("磁盘 " + label, root.getAbsolutePath());
        }
        return ApiResponse.ok(dirs);
    }
}
