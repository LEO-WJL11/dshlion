#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把"品牌 + 中文 + 直接开 code-server（Agent 在右栏）"落到 dist 里那份 code-server 上。

要点：
  1. dist\code-server 才是**打安装包用的那份**（我前面改的是用户目录里的工作副本）；
  2. product.json 的 nameShort/nameLong → LionBox（这样界面里不再显示 code-server）；
  3. locale：argv.json 写在 data\ 和 data\user-data\ 两处都放一份（哪个生效都覆盖）；
  4. launcher.ps1：**不再打开 studio.html 并排页**，而是直接开 code-server
     （http://127.0.0.1:8081）——我们的插件以 webview view 挂在右侧栏，
     激活后自动聚焦，于是"左边编辑器、右边我们的 Agent"就是同一个 VS Code 窗口，
     不是并排硬拼两个页面。
"""

import io, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CS = os.path.join(ROOT, 'dist', 'code-server')
VSC = os.path.join(CS, 'node_modules', 'code-server', 'lib', 'vscode')

# 1) 品牌
p = os.path.join(VSC, 'product.json')
if os.path.isfile(p):
    d = json.load(io.open(p, encoding='utf-8'))
    before = (d.get('nameShort'), d.get('nameLong'))
    d['nameShort'] = 'LionBox'
    d['nameLong'] = 'LionBox Studio'
    if 'applicationName' in d:
        d['applicationName'] = 'LionBox'
    io.open(p, 'w', encoding='utf-8').write(json.dumps(d, ensure_ascii=False, indent=2))
    print('dist product.json: %s → (%s, %s)' % (before, d['nameShort'], d['nameLong']))
else:
    print('! 找不到 dist 里的 product.json'); sys.exit(1)

# 2) locale
for sub in ('data', os.path.join('data', 'user-data')):
    d = os.path.join(CS, sub)
    os.makedirs(d, exist_ok=True)
    io.open(os.path.join(d, 'argv.json'), 'w', encoding='utf-8').write(
        '{\n  "locale": "zh-cn"\n}\n')
    print('dist argv.json: %s' % os.path.join(sub, 'argv.json'))

# 3) launcher：直接开 code-server（不再开并排页）
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
txt = io.open(lp, 'rb').read().decode('utf-8-sig')
start = txt.find('# ---------- 5.')
if start < 0:
    print('! launcher 找不到第 5 步'); sys.exit(1)
NEW = '''# ---------- 5. 起 code-server，并打开它（Agent 就在它右侧栏里） ----------
# 不再打开"并排拼两个页面"的 studio.html：我们的插件是以 webview view 挂在 VS Code 的
# 右侧栏（secondarySidebar）上的，激活后自动聚焦 —— 所以直接开 code-server 就是
# "左边编辑器、右边我们的 Agent"，同一个窗口。
$csEntry = Join-Path $PSScriptRoot 'code-server\\node_modules\\code-server\\out\\node\\entry.js'
$nodeExe = Join-Path $PSScriptRoot 'node\\node.exe'
$csPort = 8081
if ((Test-Path $nodeExe) -and (Test-Path $csEntry)) {
    Write-Line '     正在启动编辑器（VS Code Web 版，右侧栏是 LionBox Agent）…'
    $csArgs = @($csEntry, '--bind-addr', "127.0.0.1:$csPort", '--auth', 'none',
                '--locale', 'zh-cn', '--disable-telemetry', '--disable-update-check',
                '--user-data-dir', (Join-Path $PSScriptRoot 'code-server\\data'))
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
'''
io.open(lp, 'wb').write(b'\xef\xbb\xbf' + (txt[:start] + NEW).encode('utf-8'))
print('launcher: 已改成直接开 code-server（Agent 在右栏）')
