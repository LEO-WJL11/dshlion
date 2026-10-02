#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""修两个"第二次打开就失效"的问题：① Agent 面板不自动弹 ② 界面不是中文。

根因分析（都跟"第二次"这个条件对得上）：
  ① 面板自动弹：原来靠启动器给 code-server 传 `LIONBOX_MIXED=1`，插件在 **activate()** 里读它。
     第二次打开时窗口/扩展宿主往往**复用了上一次的进程**，activate 不再跑 → 没人去 focus 面板，
     侧栏就停在别的视图上。
     修法：给插件加 **onStartupFinished** 激活事件 —— 它保证"每次窗口启动都会激活一次"，
     激活时若带 LIONBOX_MIXED 就 focus，并且**重试几次**（code-server 冷启动慢，一次可能太早）。
  ② 中文丢失：语言/设置文件是我**打包时预置**在 data 目录里的，第二次运行时 code-server
     可能已经把 profile 重建成自己的默认值（locale 回到 en）。启动参数里的 `--locale zh-cn`
     也只在**新起的进程**上生效。
     修法：启动器**每次启动前都把 locale/settings 重新写一遍**（幂等），并且**先看 8081 是否已在跑** ——
     已在跑就只开浏览器、不要再起一个（两个实例抢同一个 data 目录正是状态被覆盖的原因）。

写回 launcher.ps1 时强制补 UTF-8 BOM（PowerShell 5.1 没 BOM 会把中文读坏，前面栽过）。
"""

import io, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
changed = []

# ---------- 1) 插件：加 onStartupFinished + focus 重试 ----------
pkg = os.path.join(ROOT, 'extensions', 'vscode', 'package.json')
d = json.loads(io.open(pkg, encoding='utf-8').read())
ev = d.get('activationEvents', [])
if 'onStartupFinished' not in ev:
    ev.append('onStartupFinished')
    d['activationEvents'] = ev
    io.open(pkg, 'w', encoding='utf-8', newline='').write(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
    changed.append('插件 activationEvents += onStartupFinished')

ext = os.path.join(ROOT, 'extensions', 'vscode', 'src', 'extension.js')
t = io.open(ext, encoding='utf-8').read()
old = """  if (process.env.LIONBOX_MIXED === '1') {
    setTimeout(() => {
      vscode.commands.executeCommand('lionbox.agent.focus').then(undefined, () => {});
    }, 1200);
  }"""
new = """  if (process.env.LIONBOX_MIXED === '1') {
    // 每次窗口启动都要把面板拉到前面来：第二次打开时扩展宿主常常是复用的，
    // activate 只跑一次，所以这里既靠 onStartupFinished 激活，又重试几次
    // （code-server 冷启动慢，太早 focus 会落空）。
    [1000, 3000, 6000, 10000].forEach((ms) => {
      setTimeout(() => {
        vscode.commands.executeCommand('lionbox.agent.focus').then(undefined, () => {});
      }, ms);
    });
  }"""
if old in t:
    t = t.replace(old, new, 1)
    io.open(ext, 'w', encoding='utf-8', newline='').write(t)
    changed.append('插件 focus 重试 4 次')
else:
    changed.append('插件 focus 段没匹配到（可能已改过）')

# ---------- 2) 启动器：每次写 locale/settings + 复用已在跑的实例 ----------
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
lt = io.open(lp, 'rb').read().decode('utf-8-sig')
anchor = "$csPort = 8081"
if anchor in lt:
    inject = '''$csPort = 8081
# ---- 每次启动都把"中文 + 我们想要的默认设置"重写一遍（幂等）----
# 为什么不能只靠打包时预置：第二次运行时 code-server 可能已经把 profile 重建成默认值，
# locale 就回到英文了。这里是运行期兜底，保证每次打开都是简体中文。
$csData = Join-Path $PSScriptRoot 'code-server\\data'
New-Item -ItemType Directory -Force -Path (Join-Path $csData 'User') | Out-Null
[System.IO.File]::WriteAllText((Join-Path $csData 'argv.json'), "{`n  `"locale`": `"zh-cn`"`n}`n", (New-Object System.Text.UTF8Encoding($false)))
$setFile = Join-Path $csData 'User\\settings.json'
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
[System.IO.File]::WriteAllText($setFile, ($set | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
# ---- 已经在跑就不再起第二个（两个实例共用同一个 data 目录会把状态互相覆盖）----
$already = Test-Http -Port $csPort -Path '/healthz' -TimeoutSec 2'''
    lt = lt.replace(anchor, inject, 1)
    lt = lt.replace("if ((Test-Path $nodeExe) -and (Test-Path $csEntry)) {",
                    "if ($already) {\n    Write-Line \"     编辑器已在运行，直接打开：http://127.0.0.1:$csPort\"\n    Start-Process \"http://127.0.0.1:$csPort\" | Out-Null\n}\nelseif ((Test-Path $nodeExe) -and (Test-Path $csEntry)) {", 1)
    io.open(lp, 'wb').write(b'\xef\xbb\xbf' + lt.encode('utf-8'))
    changed.append('启动器：每次重写 locale/settings + 复用已运行实例')
else:
    changed.append('启动器：没找到 $csPort 锚点')

print('；'.join(changed))
print('launcher BOM:', io.open(lp, 'rb').read()[:3] == b'\xef\xbb\xbf')
print('package.json 激活事件:', json.loads(io.open(pkg, encoding='utf-8').read())['activationEvents'])
