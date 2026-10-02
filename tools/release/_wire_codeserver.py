#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把安装包换成"左 code-server / 右我们的 Agent"这一套。

用户定稿：左边 code-server，右边我们的 agent，一个 EXE 装好就能用。

  1. installer/LionBox.iss：
     - 源从 dist\vscode\* 换成 dist\code-server\*（→ {app}\code-server）和 dist\node\*（→ {app}\node）；
     - 插件/语言包安装脚本保留（现在它优先给自带 VS Code 装 → 改成给自带 code-server 装）。
  2. dist/launcher.ps1：
     - 起后端 → 用自带 node 起 code-server（127.0.0.1:8081、免密、zh-cn、独立 data）→ 打开 studio.html
       （左 code-server、右我们 Agent 的拼装页）；code-server 起不来就退回浏览器开后端界面。
     - 必须写回 UTF-8 BOM（PowerShell 5.1 没 BOM 会把中文读坏）。
"""

import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ---------- 1) iss：换源 ----------
p = os.path.join(ROOT, 'installer', 'LionBox.iss')
s = io.open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
b = s.replace('\r\n', '\n') if crlf else s
old_line = [l for l in b.split('\n') if 'dist\\vscode\\*' in l]
for l in old_line:
    b = b.replace(l + '\n', '')
if 'dist\\code-server\\*' not in b:
    anchor = [l for l in b.split('\n') if 'install-vscode-ext.bat' in l and l.startswith('Source:')]
    add = ('; 左边是 VS Code 的 Web 版（code-server）+ 自带 Node 运行时：干净电脑免预装，\n'
           '; 启动器用它们拼出"左编辑器 + 右 Agent"的工作室页面。\n'
           'Source: "..\\dist\\code-server\\*"; DestDir: "{app}\\code-server"; Flags: ignoreversion recursesubdirs createallsubdirs\n'
           'Source: "..\\dist\\node\\*"; DestDir: "{app}\\node"; Flags: ignoreversion recursesubdirs createallsubdirs\n')
    b = b.replace(anchor[0], add + anchor[0], 1) if anchor else (add + b)
    if crlf: b = b.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(b)
    print('iss: 已换成 code-server + node')
else:
    print('iss: 已经是 code-server')

# ---------- 2) launcher.ps1 ----------
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
txt = io.open(lp, 'rb').read().decode('utf-8-sig')
start = txt.find('# ---------- 5. 打开界面')
if start < 0:
    print('launcher: 找不到第 5 步'); sys.exit(1)
NEW = '''# ---------- 5. 起 code-server 并打开工作室页面（左 code-server，右我们的 Agent） ----------
$csEntry = Join-Path $PSScriptRoot 'code-server\\node_modules\\code-server\\out\\node\\entry.js'
$nodeExe = Join-Path $PSScriptRoot 'node\\node.exe'
$csPort = 8081
$studioUrl = "http://127.0.0.1:$AgentPort/studio.html"
if ((Test-Path $nodeExe) -and (Test-Path $csEntry)) {
    Write-Line '     正在启动编辑器（VS Code Web 版）…'
    $csArgs = @($csEntry, '--bind-addr', "127.0.0.1:$csPort", '--auth', 'none',
                '--locale', 'zh-cn', '--disable-telemetry', '--disable-update-check',
                '--user-data-dir', (Join-Path $PSScriptRoot 'code-server\\data'))
    try { Start-Process -FilePath $nodeExe -ArgumentList $csArgs -WindowStyle Hidden } catch { }
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Http -Port $csPort -Path '/healthz' -TimeoutSec 2) { break }
    }
    Write-Line "     编辑器      : http://127.0.0.1:$csPort"
    Start-Process $studioUrl | Out-Null
}
else {
    Write-Line '     没找到自带的 code-server，先用浏览器打开界面'
    Start-Process "http://127.0.0.1:$AgentPort" | Out-Null
}
Start-Sleep -Seconds 4
'''
io.open(lp, 'wb').write(b'\xef\xbb\xbf' + (txt[:start] + NEW).encode('utf-8'))
print('launcher: 已改成起 code-server + 打开 studio.html')
