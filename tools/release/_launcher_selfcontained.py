#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""启动器改成"自包含"：默认打开我们自己的界面，不再依赖机器上装没装 VS Code。

用户的约束："不能复用机器上已有的任何东西 —— 别人拿到一台什么也没装过的电脑，
装上直接就能用。"

上一版启动器优先调 `code` 开 VS Code（复用了已装的编辑器），这违反了这个约束：
干净机器上没装 VS Code 就退化成浏览器，体验就不一致了。

【注意 BOM】launcher.ps1 必须是 **UTF-8 WITH BOM** —— PowerShell 5.1 没有 BOM 会把中文
读成乱码、整个脚本语法炸（上一版就丢过一次）。所以这个脚本全程按字节操作，写完强制补 BOM。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'dist', 'launcher.ps1')

raw = io.open(P, 'rb').read()
had_bom = raw[:3] == b'\xef\xbb\xbf'
text = raw.decode('utf-8-sig')

start = text.find('# ---------- 5. 打开界面')
if start < 0:
    print('! 找不到第 5 步那段，先看一眼文件')
    sys.exit(1)

NEW = '''# ---------- 5. 打开界面（自包含：不依赖机器上装没装任何东西） ----------
# 【为什么不再优先调 VS Code】用户的约束是"拿到一台什么也没装过的电脑，装上直接就能用"。
# 所以主路径必须是**我们自己的界面**：它自带编辑器、对话、设置、插件管理、工作区选择。
# 机器上如果碰巧装了 VS Code，那只是"多个选择"，不能当成前提。
Write-Line '     正在打开 LionBox 控制台…'
Start-Process "http://127.0.0.1:$AgentPort" | Out-Null
Write-Line "     界面地址：http://127.0.0.1:$AgentPort （收藏一下，或从开始菜单再开）"

# 可选：装了 VS Code 的话，顺手把编辑器里的面板也打开（纯附加，不影响上面的自包含路径）
$code = $null
foreach ($c in @(
    (Join-Path $env:LOCALAPPDATA 'Programs\\Microsoft VS Code\\bin\\code.cmd'),
    (Join-Path $env:ProgramFiles 'Microsoft VS Code\\bin\\code.cmd'),
    (Join-Path ${env:ProgramFiles(x86)} 'Microsoft VS Code\\bin\\code.cmd'))) {
    if ($c -and (Test-Path $c)) { $code = $c; break }
}
if ($code) {
    try {
        $env:LIONBOX_MIXED = '1'
        Start-Process -FilePath $code -ArgumentList @('--new-window') | Out-Null
        Write-Line '     另外：检测到 VS Code，已同时在编辑器里打开 Agent 面板（不需要可以关掉）'
    }
    catch {
        # 装了但调不动：不是错误，我们自己的界面已经在上面打开了
    }
}
Start-Sleep -Seconds 4
'''

text = text[:start] + NEW
io.open(P, 'wb').write(b'\xef\xbb\xbf' + text.encode('utf-8'))
print('launcher.ps1 已改成自包含主路径（并强制写回 UTF-8 BOM）')
print('之前有 BOM:', had_bom)
