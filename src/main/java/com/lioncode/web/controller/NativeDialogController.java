package com.lioncode.web.controller;

import com.lioncode.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows原生文件夹选择
 * 
 * 使用 Vista+ 的 IFileDialog（现代 Explorer 风格的公共对话框，
 * 带地址栏、收藏夹、左侧导航栏——即"Windows标准样式"的文件夹选择窗口），
 * 通过 PowerShell + C# COM 互操作实现。
 * 
 * 结果传递：PowerShell 把选择结果写入临时文件（UTF-8，格式 "状态|路径"），
 * Java 读取该文件获取结果。不依赖控制台管道，避免隐藏窗口/编码问题。
 * 
 * 失败时回退到传统 FolderBrowserDialog（仅当现代对话框初始化失败，
 * 用户取消不触发回退）。
 */
@RestController
@RequestMapping("/api/native-dialog")
public class NativeDialogController {

    private static final Logger log = LoggerFactory.getLogger(NativeDialogController.class);

    /** 防止并发弹窗：同一时间只允许一个文件夹选择对话框 */
    private static final AtomicBoolean DIALOG_OPEN = new AtomicBoolean(false);

    /**
     * PowerShell 脚本：现代 IFileDialog 文件夹选择器
     * 
     * - 以 UTF-8 BOM 写入临时 .ps1 文件，避免中文乱码
     * - 初始路径通过环境变量 LIONCODE_INITIAL_DIR 传入
     * - 选择结果写入环境变量 LIONCODE_RESULT_FILE 指定的文件
     * - 结果格式: "OK|路径" / "CANCEL|" / "FAIL|错误信息"
     */
    private static final String PICKER_SCRIPT = """
        $ErrorActionPreference = 'Stop'
        $resultFile = $env:LIONCODE_RESULT_FILE

        function Write-Result([string]$status, [string]$detail) {
            try {
                $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
                [System.IO.File]::WriteAllText($resultFile, ($status + '|' + $detail), $utf8NoBom)
            } catch {
                # 结果文件写失败时兜底输出到stdout
                Write-Output ($status + '|' + $detail)
            }
        }

        try {
            Add-Type -TypeDefinition @'
        using System;
        using System.Runtime.InteropServices;

        public static class FolderPicker {

            [ComImport]
            [Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")]
            public class FileOpenDialogRCW { }

            [ComImport]
            [Guid("42F85136-DB7E-439C-85F1-E4075D135FC8")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IFileOpenDialog {
                [PreserveSig] int Show(IntPtr parent);
                void SetFileTypes(uint cFileTypes, [In] IntPtr rgFilterSpec);
                void SetFileTypeIndex(uint iFileType);
                void GetFileTypeIndex(out uint piFileType);
                void Advise(IntPtr pfde, out uint pdwCookie);
                void Unadvise(uint dwCookie);
                void SetOptions(uint fos);
                void GetOptions(out uint pfos);
                void SetDefaultFolder(IShellItem psi);
                void SetFolder(IShellItem psi);
                void GetFolder(out IShellItem ppsi);
                void GetCurrentSelection(out IShellItem ppsi);
                void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
                void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
                void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
                void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
                void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
                void GetResult(out IShellItem ppsi);
                void AddPlace(IShellItem psi, uint fdap);
                void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
                void Close(int hr);
                void SetClientGuid(ref Guid guid);
                void ClearClientData();
                void SetFilter(IntPtr pFilter);
                void GetResults(out IShellItemArray ppenum);
                void GetSelectedItems(out IShellItemArray ppsai);
            }

            [ComImport]
            [Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IShellItem {
                void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
                void GetParent(out IShellItem ppsi);
                void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
                void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
                void Compare(IShellItem psi, uint hint, out int piOrder);
            }

            [ComImport]
            [Guid("B63EA76D-1F85-456F-A19C-48159EFA858B")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            public interface IShellItemArray { }

            [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
            private static extern int SHCreateItemFromParsingName(
                [MarshalAs(UnmanagedType.LPWStr)] string pszPath,
                IntPtr pbc,
                [In] ref Guid riid,
                [MarshalAs(UnmanagedType.Interface)] out IShellItem ppv);

            private static string GetPath(IShellItem item) {
                if (item == null) return null;
                try {
                    string path;
                    // SIGDN_FILESYSPATH；失败时COM互操作会抛出异常
                    item.GetDisplayName(0x80058000, out path);
                    return string.IsNullOrEmpty(path) ? null : path;
                } catch {
                    return null;
                }
            }

            public static string PickFolder(string title, string initialPath) {
                var dialog = (IFileOpenDialog)new FileOpenDialogRCW();
                // FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST
                uint options = 0x20 | 0x40 | 0x800;
                dialog.SetOptions(options);
                if (!string.IsNullOrEmpty(title)) {
                    dialog.SetTitle(title);
                }
                if (!string.IsNullOrEmpty(initialPath)) {
                    try {
                        IShellItem folder;
                        Guid shellItemGuid = typeof(IShellItem).GUID;
                        int hr = SHCreateItemFromParsingName(initialPath, IntPtr.Zero, ref shellItemGuid, out folder);
                        if (hr == 0 && folder != null) {
                            dialog.SetFolder(folder);
                        }
                    } catch { }
                }
                int showHr = dialog.Show(IntPtr.Zero);
                if (showHr == unchecked((int)0x800704C7)) {
                    return null; // 用户取消：正常返回null
                }
                if (showHr != 0) {
                    throw new Exception("Show failed, HRESULT=0x" + showHr.ToString("X8"));
                }
                IShellItem result = null;
                try {
                    dialog.GetResult(out result);
                } catch {
                    result = null;
                }
                string path = GetPath(result);
                if (path == null) {
                    // GetResult失败时尝试GetCurrentSelection兜底
                    IShellItem selected = null;
                    try {
                        dialog.GetCurrentSelection(out selected);
                    } catch {
                        selected = null;
                    }
                    path = GetPath(selected);
                    if (path == null) {
                        throw new Exception("GetResult/GetCurrentSelection failed");
                    }
                }
                return path;
            }
        }
        '@
        } catch {
            Write-Result 'FAIL' ("AddType编译失败: " + $_.Exception.Message)
            exit 1
        }

        $initial = $env:LIONCODE_INITIAL_DIR
        $picked = $null

        # 首选：现代 Explorer 风格文件夹选择窗口
        try {
            $picked = [FolderPicker]::PickFolder('选择工作区目录', $initial)
        } catch {
            # 现代对话框初始化/运行失败 → 回退传统对话框
            try {
                Add-Type -AssemblyName System.Windows.Forms
                $d = New-Object System.Windows.Forms.FolderBrowserDialog
                $d.Description = '选择工作区目录'
                $d.ShowNewFolderButton = $true
                if ($initial -and (Test-Path -LiteralPath $initial)) {
                    $d.SelectedPath = $initial
                }
                if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                    Write-Result 'OK' $d.SelectedPath
                    exit 0
                }
                Write-Result 'CANCEL' ''
            } catch {
                Write-Result 'FAIL' ("回退对话框失败: " + $_.Exception.Message)
            }
            exit 1
        }

        if ($picked) {
            Write-Result 'OK' $picked
        } else {
            # 用户在现代化对话框中点了取消
            Write-Result 'CANCEL' ''
        }
        """;

    /**
     * 打开Windows标准样式的文件夹选择对话框（现代 Explorer 风格）
     */
    @GetMapping("/select-directory")
    public ApiResponse<String> selectDirectory(
            @RequestParam(value = "initialPath", defaultValue = "") String initialPath) {
        // 已有对话框打开时直接拒绝，避免连续点击弹出多个窗口
        if (!DIALOG_OPEN.compareAndSet(false, true)) {
            return ApiResponse.error("文件夹选择窗口已打开，请先完成或关闭当前窗口");
        }
        Path tempDir = null;
        try {
            // 临时目录：脚本 + 结果文件
            tempDir = Files.createTempDirectory("lioncode-picker");
            Path scriptFile = tempDir.resolve("picker.ps1");
            Path resultFile = tempDir.resolve("result.txt");
            Files.writeString(scriptFile, "\uFEFF" + PICKER_SCRIPT, StandardCharsets.UTF_8);

            // -WindowStyle Hidden：隐藏PowerShell控制台窗口，只显示文件夹对话框
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-STA", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.toString()
            );
            pb.environment().put("LIONCODE_INITIAL_DIR", initialPath == null ? "" : initialPath);
            pb.environment().put("LIONCODE_RESULT_FILE", resultFile.toString());
            pb.redirectErrorStream(true);
            // 【顺序很关键】必须先把 stdout 重定向到文件，再去 waitFor(超时)。
            // 原实现是"先在当前线程 readLine() 读完 stdout，再 waitFor(300, SECONDS)"：
            // readLine() 会一直阻塞到子进程关闭 stdout，也就是**对话框关掉为止** ——
            // 用户把选择窗口晾在那儿不点，这个 HTTP 线程就永远卡住，waitFor 的超时形同虚设；
            // 而且 finally 里的 DIALOG_OPEN 复位也永远不会执行，之后所有请求都被
            // "文件夹选择窗口已打开"拒绝，只能重启应用。
            Path stdoutFile = tempDir.resolve("stdout.txt");
            pb.redirectOutput(stdoutFile.toFile());

            Process process = pb.start();

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            String stdoutText = "";
            try {
                if (Files.exists(stdoutFile)) {
                    stdoutText = Files.readString(stdoutFile, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.debug("读取选择器 stdout 失败（忽略）: {}", e.getMessage());
            }
            StringBuilder stdout = new StringBuilder(stdoutText);

            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                log.warn("文件夹选择对话框超时，stdout: {}", stdout);
                return ApiResponse.error("对话框等待超时（5 分钟），已关闭；请重新打开并完成选择");
            }

            int exitCode = process.exitValue();
            // 读取结果文件
            String result = null;
            if (Files.exists(resultFile)) {
                result = Files.readString(resultFile, StandardCharsets.UTF_8);
                // 防御：去除可能的BOM和空白
                result = result.replace("\uFEFF", "").trim();
            }
            log.info("文件夹选择脚本结束 exit={} result={} stdout={}",
                exitCode, result, stdout.toString().trim());

            // 进程异常退出（崩溃/被杀）→ 明确报错，而非误判为取消
            if (exitCode != 0 && (result == null || !result.startsWith("OK|"))) {
                log.error("文件夹选择脚本异常退出: exit={} result={} stdout={}",
                    exitCode, result, stdout.toString().trim());
                return ApiResponse.error("文件夹选择窗口异常退出（exit=" + exitCode + "）");
            }

            if (result == null || result.isEmpty()) {
                // 结果文件缺失：极老路径或异常，看stdout兜底
                String fallback = stdout.toString().trim();
                if (!fallback.isEmpty() && !fallback.startsWith("FAIL")) {
                    log.info("结果文件为空，使用stdout兜底: {}", fallback);
                    result = "OK|" + fallback;
                }
            }

            if (result != null && result.startsWith("OK|")) {
                String selected = result.substring(3).trim();
                if (!selected.isEmpty()) {
                    log.info("用户选择目录: {}", selected);
                    return ApiResponse.ok("目录已选择", selected);
                }
            }

            if (result != null && result.startsWith("FAIL|")) {
                String msg = result.substring(5).trim();
                log.error("文件夹选择对话框失败: {}", msg);
                return ApiResponse.error("打开对话框失败: " + msg);
            }

            // CANCEL 或未知
            log.info("用户取消文件夹选择或未选择目录 (result={}, stdout={})", result, stdout.toString().trim());
            return ApiResponse.error("用户取消选择");

        } catch (Exception e) {
            log.error("打开文件夹选择对话框失败", e);
            return ApiResponse.error("打开对话框失败: " + e.getMessage());
        } finally {
            DIALOG_OPEN.set(false);
            if (tempDir != null) {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响主流程
                }
            }
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
