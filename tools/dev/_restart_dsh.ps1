# restart-dsh.ps1 —— 延后重启 DSH，让新装的插件生效
#
# 为什么要延后：
#   我（当前这个 agent 进程）就跑在 DSH 里面，直接杀 DSH 等于把自己也杀了，
#   那样就没法把结果告诉用户了。所以由这个脱离的脚本等一会儿再动手。

$ErrorActionPreference = 'Continue'
$log = Join-Path $env:TEMP 'dsh-restart.log'
function L($m) {
    Add-Content -Path $log -Value ('[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $m) -Encoding utf8
}

L '=== 重启任务开始 ==='
L '等 25 秒，让当前会话把最后一条消息发出去'
Start-Sleep -Seconds 25

# 1) 停掉旧 DSH（只杀自己，不用 /T：/T 会连带杀掉我们自己起的 LionBox 等子进程）
$old = Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
       Where-Object { $_.CommandLine -like '*dsh*bin.js*web*' -and $_.CommandLine -notlike '*3099*' }
if (-not $old) { L '没找到旧的 DSH 进程（可能已经自己退了）' }
foreach ($p in $old) {
    L ('停止旧 DSH  PID ' + $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 4

# 2) 等 3080 端口真正释放
for ($i = 0; $i -lt 30; $i++) {
    if (-not (Get-NetTCPConnection -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Seconds 1
}
L '端口 3080 已释放'

# 3) 清掉残留的测试实例（3099）
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -like '*3099*' } |
    ForEach-Object { L ('停止测试实例 PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# 4) 起新的 DSH（工作目录与桌面那个 .bat 保持一致）
$out = Join-Path $env:TEMP 'dsh-web.log'
$err = Join-Path $env:TEMP 'dsh-web.err'
L '启动新的 DSH…'
$p = Start-Process -FilePath 'node' `
        -ArgumentList @('C:\Users\Leo\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\lib\bin.js', 'web') `
        -WorkingDirectory (Join-Path $env:USERPROFILE 'Desktop') `
        -RedirectStandardOutput $out -RedirectStandardError $err `
        -PassThru -WindowStyle Hidden
L ('新 DSH PID ' + $p.Id)

# 5) 等它就绪，并在日志里确认插件真的加载了
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    if ($p.HasExited) { L ('[错误] 新 DSH 进程退出，退出码 ' + $p.ExitCode); break }
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:3080/' -TimeoutSec 3 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $ok = $true; L '新 DSH 已就绪（http://127.0.0.1:3080）'; break }
    } catch { }
}

if ($ok) {
    Start-Sleep -Seconds 3
    $hit = Select-String -Path $out -Pattern 'lion-sound' -ErrorAction SilentlyContinue
    if ($hit) {
        foreach ($h in $hit) { L ('  ' + $h.Line.Trim()) }
        L '✅ 鸡叫插件已加载'
    } else {
        L '⚠️ 日志里没看到 lion-sound —— 插件可能没生效，检查 ' + $out
    }
    if ((Test-Path $err) -and (Get-Item $err).Length -gt 0) {
        L '--- stderr 尾巴 ---'
        Get-Content $err -Tail 10 | ForEach-Object { L ('  ' + $_) }
    }
} else {
    L '[错误] 新 DSH 没起来。手动启动方式：在桌面双击「DeepSeek Harness.bat」'
    L ('  日志：' + $out + ' / ' + $err)
}
L '=== 重启任务结束 ==='
