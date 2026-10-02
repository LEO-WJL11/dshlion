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
$ModelName    = 'lion-models1'
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
    $runtimeArgs = @()
# 安装时勾了"不下载本地模型"就写在这个文件里（见 installer/LionBox.iss 的 [INI]）
$optFile = Join-Path $Root 'lionbox-options.ini'
if (Test-Path $optFile) {
    $optTxt = Get-Content $optFile -Raw -ErrorAction SilentlyContinue
    if ($optTxt -match 'autoDownload\s*=\s*0') {
        $runtimeArgs += '--lionbox.runtime.auto-download=false'
    }
}
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
# ---------- 5. 起 code-server，并打开它（Agent 就在它右侧栏里） ----------
# 不再打开"并排拼两个页面"的 studio.html：我们的插件是以 webview view 挂在 VS Code 的
# 右侧栏（secondarySidebar）上的，激活后自动聚焦 —— 所以直接开 code-server 就是
# "左边编辑器、右边我们的 Agent"，同一个窗口。
$csEntry = Join-Path $PSScriptRoot 'code-server\node_modules\code-server\out\node\entry.js'
$nodeExe = Join-Path $PSScriptRoot 'node\node.exe'
$csPort = 8081
# ---- 每次启动都把"中文 + 我们想要的默认设置"重写一遍（幂等）----
# 为什么不能只靠打包时预置：第二次运行时 code-server 可能已经把 profile 重建成默认值，
# locale 就回到英文了。这里是运行期兜底，保证每次打开都是简体中文。
$csData = Join-Path $PSScriptRoot 'code-server\data'
# locale 同时写到 code-server 可能读取的每个位置（不同版本读的地方不一样，全给上不亏）
$argvJson = "{`n  `"locale`": `"zh-cn`"`n}`n"
foreach ($d in @($csData, (Join-Path $csData 'data'), (Join-Path $csData 'user-data'),
                 (Join-Path $csData 'User'), (Join-Path $PSScriptRoot 'code-server'))) {
    try {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $d 'argv.json'), $argvJson,
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }
}
New-Item -ItemType Directory -Force -Path (Join-Path $csData 'User') | Out-Null
[System.IO.File]::WriteAllText((Join-Path $csData 'argv.json'), "{`n  `"locale`": `"zh-cn`"`n}`n", (New-Object System.Text.UTF8Encoding($false)))
$setDirs = @((Join-Path $csData 'User'), (Join-Path $csData 'data\User'))
foreach ($sd in $setDirs) { try { New-Item -ItemType Directory -Force -Path $sd | Out-Null } catch { } }
$setFile = Join-Path $csData 'User\settings.json'
$set = @{}
if (Test-Path $setFile) {
    try { (Get-Content $setFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $set[$_.Name] = $_.Value } } catch { }
}
$set['locale'] = 'zh-cn'
$set['chat.disableAIFeatures'] = $true
$set['chat.commandCenter.enabled'] = $false
$set['workbench.secondarySideBar.defaultVisibility'] = 'visible'
$set['telemetry.telemetryLevel'] = 'off'
$set['workbench.startupEditor'] = 'none'
foreach ($sd in $setDirs) {
    try { [System.IO.File]::WriteAllText((Join-Path $sd 'settings.json'), ($set | ConvertTo-Json),
            (New-Object System.Text.UTF8Encoding($false))) } catch { }
}
# ---- 已经在跑就不再起第二个（两个实例共用同一个 data 目录会把状态互相覆盖）----
$already = Test-Http -Port $csPort -Path '/healthz' -TimeoutSec 2
if ($already) {
    Write-Line "     编辑器已在运行，直接打开：http://127.0.0.1:$csPort"
    Start-Process "http://127.0.0.1:$csPort" | Out-Null
    # 自检证据：中文没生效时，看这个文件就知道卡在哪一环
    try {
        $lines = @(
            "code-server 数据目录: $csData",
            "argv.json: " + (Get-Content (Join-Path $csData 'argv.json') -Raw -ErrorAction SilentlyContinue),
            "settings.json: " + (Get-Content (Join-Path $csData 'User\settings.json') -Raw -ErrorAction SilentlyContinue),
            "已装扩展: " + ((Get-ChildItem (Join-Path $csData 'extensions') -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name) -join ', ')
        )
        [System.IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'lionbox-selfcheck.txt'), $lines,
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }
}
elseif ((Test-Path $nodeExe) -and (Test-Path $csEntry)) {
    # 让插件知道用户是从 LionBox 进来的，激活后自动把右侧栏的 Agent 面板展开
    $env:LIONBOX_MIXED = '1'
    Write-Line '     正在启动编辑器（VS Code Web 版，右侧栏是 LionBox Agent）…'
    $csArgs = @($csEntry, '--bind-addr', "127.0.0.1:$csPort", '--auth', 'none',
                '--locale=zh-cn', '--disable-telemetry', '--disable-update-check',
                '--user-data-dir', (Join-Path $PSScriptRoot 'code-server\data'))
    try { Start-Process -FilePath $nodeExe -ArgumentList $csArgs -WindowStyle Hidden } catch { }
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Http -Port $csPort -Path '/healthz' -TimeoutSec 2) { break }
    }
    Write-Line "     编辑器      : http://127.0.0.1:$csPort"
    Start-Process "http://127.0.0.1:$csPort" | Out-Null
    Write-Line '     右侧栏没自动出来就按 Ctrl+Shift+P -> LionBox: 打开 Agent 面板（右侧栏）'
}
else {
    Write-Line '     没找到自带的 code-server，先用浏览器打开界面'
    Start-Process "http://127.0.0.1:$AgentPort" | Out-Null
}
Start-Sleep -Seconds 3
