# ============================================================
#  LionBox 停止脚本
#
#  停止本地模型服务与 Agent。模型服务占用约 9GB 内存（256K 上下文还要加 KV 缓存），
#  不用时建议停止以释放内存。
#
#  实现说明（v1.1 修复两处致命问题）：
#    1) 旧版 .bat 缺少 setlocal enabledelayedexpansion，for 循环里的
#       !errorlevel! 不会展开，判断恒为假；
#    2) 旧版用 wmic 匹配命令行定位 Agent 进程，而 Windows 11 24H2
#       起 wmic 已被系统移除，命令根本不返回结果。
#    两者叠加 → 点「停止」后 Agent 进程一直残留。
#
#  现在逻辑挪到本脚本里（.bat 只当壳），用 Get-CimInstance 匹配
#  jar 名定位进程，不依赖 wmic、不依赖延迟展开，任何 Windows 都能用。
#
#  注意：本文件必须保存为「UTF-8 带 BOM」。
# ============================================================

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Line($t) { Write-Host $t }

Write-Line ''
Write-Line '  正在停止 LionBox ...'
Write-Line ''

# ---------- 1. 本地模型服务 ----------
$llama = Get-Process -Name 'llama-server' -ErrorAction SilentlyContinue
if ($llama) {
    $llama | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Line '  [完成] 本地模型服务已停止'
}
else {
    Write-Line '  [跳过] 本地模型服务未在运行'
}

# ---------- 2. Agent ----------
# 主路径：按命令行里的 jar 名定位（能同时干掉 javapath 外壳进程与真正的 JVM）
$killed = 0
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue
foreach ($p in $procs) {
    if ($p.CommandLine -and $p.CommandLine -like '*lion-code-agent-harness*') {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        $killed++
    }
}

# 兜底路径：万一命令行读不到，就按监听端口 8080 找 java 进程
if ($killed -eq 0) {
    $listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    foreach ($l in $listeners) {
        $owner = Get-Process -Id $l.OwningProcess -ErrorAction SilentlyContinue
        if ($owner -and $owner.ProcessName -match '^(java|javaw)$') {
            Stop-Process -Id $owner.Id -Force -ErrorAction SilentlyContinue
            $killed++
        }
    }
}

if ($killed -gt 0) {
    Write-Line '  [完成] Agent 已停止'
}
else {
    Write-Line '  [跳过] Agent 未在运行'
}

Write-Line ''
Write-Line '  LionBox 已停止。'
Write-Line ''
