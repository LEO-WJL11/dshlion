# ============================================================
#  LionBox 启动器 (v1.1)
#
#  用户只需双击「启动LionBox.bat」，它会调用本脚本完成：
#    1. 检测 Java 运行环境
#    2. 检查本地模型服务（【不预加载】：模型在用户发出第一条消息时才加载）
#    3. 启动 Lion-Code Agent（启动时不加载任何模型）
#    4. 打开界面
#
#  两种运行模式（在界面「设置 → 模型服务」里切换）：
#    - 本地模型：完全离线，第一条消息时才把 8.9GB 模型拉起来
#    - 自定义 API：填自己的 Base URL / Key / 模型名，本地模型永不加载
# ============================================================

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root         = Split-Path -Parent $MyInvocation.MyCommand.Path
$ModelPort    = 8788
$AgentPort    = 8080
$ModelFile    = 'lion-merged-Q8_0.gguf'
$ModelName    = 'MiMo-V2.6-Distill-Qwen-9B'
$JarName      = 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar'
$Runtime      = Join-Path $Root 'runtime-vulkan\llama-server.exe'
$CtxSize      = 262144
$KvType       = 'q4_0'
$ModelLog     = Join-Path $env:TEMP 'lionbox-model.log'
$AgentLog     = Join-Path $env:TEMP 'lionbox-agent.log'

# 读取用户配置里的运行模式：local = 本地模型，custom = 用户自己的 API
$ProviderMode = 'local'
$CfgFile      = Join-Path $env:USERPROFILE '.lioncode\app-config.json'
if (Test-Path $CfgFile) {
    try {
        $cfgObj = Get-Content $CfgFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($cfgObj.providerMode) { $ProviderMode = [string]$cfgObj.providerMode }
    } catch { }
}

function Write-Line($t) { Write-Host $t }

function Test-Http {
    param([int]$Port, [int]$TimeoutSec = 3)
    # 注意：llama.cpp 的根路径返回 404，必须优先探测 /health 与 /v1/models，
    # 否则会把「模型已就绪」误判为「未就绪」并反复重启服务。
    foreach ($path in @('/health', '/v1/models', '/')) {
        try {
            $null = Invoke-WebRequest -Uri "http://127.0.0.1:$Port$path" -TimeoutSec $TimeoutSec -UseBasicParsing -ErrorAction Stop
            return $true
        } catch { }
    }
    return $false
}

function Wait-Http {
    param([int]$Port, [int]$TimeoutSec, [string]$Label)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        if (Test-Http -Port $Port) { return $true }
        Start-Sleep -Seconds 3
        Write-Host -NoNewline '.'
    }
    return $false
}

Write-Line ''
Write-Line '  ============================================'
Write-Line '     LionBox  -  本地 AI 编程助手'
Write-Line '  ============================================'
Write-Line ''

# ---------- 1. Java ----------
# 优先用安装包里自带的精简 JRE（runtime-jre\）：
# 用户机器上没装 Java 也能直接跑，这才是真的"开箱即用"。
# 包里没有才回落到系统 Java（自建/绿色版场景）。
$java = $null
$javaFrom = ''
$bundled = Join-Path $Root 'runtime-jre\bin\javaw.exe'
if (Test-Path $bundled) {
    $java = $bundled
    $javaFrom = '随包自带'
}
if (-not $java) {
    foreach ($cand in @('javaw', 'java')) {
        $c = Get-Command $cand -ErrorAction SilentlyContinue
        if ($c) { $java = $c.Source; $javaFrom = '系统已安装'; break }
    }
}
if (-not $java) {
    $jre = Get-ChildItem "$env:ProgramFiles\Java","$env:ProgramFiles\Eclipse Adoptium" -Filter 'javaw.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jre) { $java = $jre.FullName; $javaFrom = '系统已安装' }
}
if (-not $java) {
    Write-Line '  [错误] 未找到 Java 运行环境（安装包里的 runtime-jre 也不在）。'
    Write-Line ''
    Write-Line '  正常安装的 LionBox 自带运行时，不该出现这个提示。'
    Write-Line '  如果是手动解压的文件，请确认 runtime-jre\bin\javaw.exe 还在；'
    Write-Line '  或者装一个 Java 21+ 再重新运行：'
    Write-Line '    https://adoptium.net/temurin/releases/'
    Write-Line ''
    Read-Host '  按回车键退出'
    exit 1
}
Write-Line "  [1/4] Java 运行环境 ............. 就绪（$javaFrom）"

# ---------- 2. 模型服务 ----------
# 【重要改动】这里不再预加载模型。
#
# 以前是启动器在开机时就把 8.9GB 的 GGUF 拉进显存，用户哪怕只是想看看界面、
# 或者打算用自己的 API，也得先干等 30~90 秒，显存也一直被占着。
#
# 现在：模型由 Agent 在**用户发出第一条消息**时按需拉起
# （见 LocalModelRuntime），而且只在「本地模型」模式下才会拉；
# 如果用户选了「自定义 API」，本地模型永远不会被加载。
#
# 如果检测到模型已经在跑（比如用户手动点了立即加载模型），这里就照常显示。
if (Test-Http -Port $ModelPort) {
    Write-Line "  [2/4] 本地模型服务 ............. 已在运行"
}
elseif ($ProviderMode -eq 'custom') {
    # 用户选了「自定义 API」，本地模型永远不会被加载，不必提示等待
    Write-Line "  [2/4] 本地模型服务 ............. 不使用（当前为自定义 API 模式）"
}
else {
    Write-Line "  [2/4] 本地模型服务 ............. 待命中（首条消息时自动加载）"
}

Write-Line ''
Write-Line "  [3/4] 模型 ..................... $ModelName"
# 安装包不再内置权重，所以这里按实际情况报：本地有就报大小，没有就说明会自动下载。
$ModelPath = Join-Path $Root $ModelFile
if (Test-Path $ModelPath) {
    $ModelGb = '{0:N2}' -f ((Get-Item $ModelPath).Length / 1GB)
    $Quant = if ($ModelFile -match 'Q(\d)') { "Q$($Matches[1])" } else { '未知' }
    Write-Line "        量化 ..................... $Quant（$ModelFile，$ModelGb GB）"
} else {
    $OtherGguf = Get-ChildItem -Path $Root -Filter '*.gguf' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($OtherGguf) {
        Write-Line "        权重 ..................... $($OtherGguf.Name)（配置里写的是 $ModelFile，程序会自动改用目录里现成的这个）"
    } else {
        Write-Line "        权重 ..................... 未下载 —— 首次用到时自动从 ModelScope 获取"
        Write-Line "                                     $ModelFile（约 8.9GB，只下一次）"
        Write-Line "                                     想先下好：界面「设置 → 模型来源 → 下载模型」"
    }
}
Write-Line "        上下文 ................... $( '{0:N0}' -f $CtxSize ) tokens（原生 256K，KV $KvType 量化）"

# ---------- 3. Agent ----------
if (Test-Http -Port $AgentPort) {
    Write-Line "  [4/4] Agent ................... 已在运行"
}
else {
    $jarPath = Join-Path $Root $JarName
    if (-not (Test-Path $jarPath)) {
        Write-Line "  [错误] 找不到程序文件： $JarName"
        Read-Host '  按回车键退出'
        exit 1
    }
    Write-Line "  [4/4] Agent ................... 正在启动"
    Start-Process -FilePath $java -ArgumentList @(
        '-Djava.awt.headless=false',
        '-Dfile.encoding=UTF-8',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '-jar', $jarPath
    ) -WorkingDirectory $Root -WindowStyle Hidden `
      -RedirectStandardOutput $AgentLog -RedirectStandardError "$AgentLog.err" | Out-Null

    Write-Host -NoNewline '        '
    $ok = Wait-Http -Port $AgentPort -TimeoutSec 120 -Label 'agent'
    Write-Line ''
    if (-not $ok) {
        Write-Line '  [错误] Agent 启动失败。'
        Write-Line "  日志： $AgentLog"
        if (Test-Path "$AgentLog.err") { Get-Content "$AgentLog.err" -Tail 25 | ForEach-Object { Write-Line "    $_" } }
        Read-Host '  按回车键退出'
        exit 1
    }
}

# ---------- 4. 打开界面 ----------
Write-Line ''
Write-Line '  ============================================'
Write-Line '     启动完成，正在打开界面'
Write-Line '  ============================================'
Write-Line "     界面      : http://127.0.0.1:$AgentPort"
Write-Line "     模型端点  : http://127.0.0.1:$ModelPort/v1"
if ($ProviderMode -eq 'custom') {
    Write-Line '     运行模式  : 自定义 API（本地模型不加载）'
}
else {
    Write-Line '     运行模式  : 本地模型（首条消息时加载）'
}
Write-Line '  ============================================'
Write-Line ''
Start-Process "http://127.0.0.1:$AgentPort" | Out-Null
Start-Sleep -Seconds 4
