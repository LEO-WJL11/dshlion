# 一步出包：编译（16 线程，**全量清缓存重编**）+ 打包（16 线程）
#
# 用法：  pwsh -File _build.ps1            # 清 target 全量重编 + 出包
#         pwsh -File _build.ps1 -SkipMvn   # 只出包（jar 已经编译好了）
#         pwsh -File _build.ps1 -MvnOnly   # 只编译并把 jar 复制到 dist
#         pwsh -File _build.ps1 -NoClean   # 增量编译（快，但见下面的说明）
#
# 为什么默认全量清缓存重编：Java 26 那边通知说改完代码要整项目重编、清掉编译缓存，
# 否则可能出问题（增量编译可能留下不一致的 class）。清一次 target 才 37 MB、重编 5 秒，
# 这点代价换"打出来的包一定是当前源码"很值 —— 之前"源码改了包里还是旧界面"也栽过一次。
#
# 为什么这个脚本必须在：安装包打的是 **dist 里那份 jar**，不是 target 里刚编译的。
# 顺序固定成 编译 → 复制到 dist → 出包，少一步就会装出旧的。

param(
    [int]$Threads = 16,
    [switch]$SkipMvn,
    [switch]$MvnOnly,
    [switch]$NoClean
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }
$mvn  = 'C:\Users\Leo\.maven\apache-maven-3.9.10\bin\mvn.cmd'
$iscc = 'C:\Users\Leo\is6573\ISCC.exe'
$jarName = 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar'

function Stamp($msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg)
}

# ---------- 1. 编译 ----------
if (-not $SkipMvn) {
    $goals = if ($NoClean) { 'package' } else { 'clean', 'package' }
    Stamp ("编译：mvn -o -T {0} -DskipTests {1}" -f $Threads, ($goals -join ' '))
    $sw = [Diagnostics.Stopwatch]::StartNew()
    # -T $Threads：Maven 并行构建线程数；-o 离线（少一次元数据往返，快几秒）
    & $mvn -o -T $Threads -DskipTests @goals
    if ($LASTEXITCODE -ne 0) {
        Stamp "mvn 失败（exit $LASTEXITCODE）"
        exit 1
    }
    $sw.Stop()
    Stamp ("编译完成：{0:N1} 秒" -f $sw.Elapsed.TotalSeconds)

    # 关键一步：把 target 的 jar 复制到 dist（安装包打的是 dist 里那份）
    $src  = Join-Path $root "target\$jarName"
    $dest = Join-Path $root "dist\$jarName"
    Copy-Item $src $dest -Force
    Stamp ("jar 已复制到 dist：{0:N0} 字节" -f (Get-Item $dest).Length)

    # 内置技能也要进 dist：ISCC 打的是 dist\skills\*，不放进去用户装完技能列表就是空的
    $skillsSrc = Join-Path $root 'skills'
    if (Test-Path $skillsSrc) {
        $skillsDest = Join-Path $root 'dist\skills'
        if (Test-Path $skillsDest) { Remove-Item $skillsDest -Recurse -Force }
        Copy-Item $skillsSrc $skillsDest -Recurse -Force
        $n = (Get-ChildItem $skillsDest -Recurse -File).Count
        Stamp ("skills 已复制到 dist：{0} 个文件" -f $n)
    }
}

if ($MvnOnly) { exit 0 }

# ---------- 2. 打包 ----------
$iss = Join-Path $root 'installer\LionBox.iss'
$ver = (Select-String -Path $iss -Pattern '#define AppVersion\s+"([^"]+)"').Matches[0].Groups[1].Value
Stamp "打包：ISCC（Compression=lzma2/ultra64，LZMANumBlockThreads=$Threads）版本 $ver"
$sw = [Diagnostics.Stopwatch]::StartNew()
& $iscc $iss | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) {
    Stamp "ISCC 失败（exit $LASTEXITCODE）"
    exit 1
}
$sw.Stop()
$exe = Join-Path $root ("installer\release\LionBox-Setup-{0}.exe" -f $ver)
if (-not (Test-Path $exe)) { Stamp "没找到产物 $exe"; exit 1 }
$size = (Get-Item $exe).Length
$sha  = (Get-FileHash $exe -Algorithm SHA256).Hash
Stamp ("打包完成：{0:N1} 秒" -f $sw.Elapsed.TotalSeconds)
Write-Host ("  包：{0}" -f $exe)
Write-Host ("  体积：{0:N0} 字节（{1:N1} MB）" -f $size, ($size / 1MB))
Write-Host ("  sha256：{0}" -f $sha)
