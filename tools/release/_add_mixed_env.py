#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""给 launcher.ps1 加上 LIONBOX_MIXED=1（让 code-server 里的 Agent 面板自己弹出来）。

【为什么要单独写个文件】上一次我用 PowerShell 内联 python -c 干这事，引号被 PS 吃掉、
脚本语法错，结果**包照出、但那个环境变量没加上**（LIONBOX_MIXED 缺失 → 右栏不会自动展开）。
用文件就避开这层引号地狱。写回时保留 UTF-8 BOM（PowerShell 5.1 识中文要靠它）。
"""

import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
p = os.path.join(ROOT, 'dist', 'launcher.ps1')
t = io.open(p, 'rb').read().decode('utf-8-sig')

if 'LIONBOX_MIXED' in t:
    print('已包含 LIONBOX_MIXED，跳过')
    sys.exit(0)

anchor = "    Write-Line '     正在启动编辑器"
if anchor not in t:
    print('! 找不到锚点，先看一眼 launcher.ps1 的第 5 步')
    sys.exit(1)

t = t.replace(anchor,
              '    # 让插件知道用户是从 LionBox 进来的，激活后自动把右侧栏的 Agent 面板展开\n'
              "    $env:LIONBOX_MIXED = '1'\n" + anchor, 1)
io.open(p, 'wb').write(b'\xef\xbb\xbf' + t.encode('utf-8'))
print('已加 LIONBOX_MIXED=1（并保留 UTF-8 BOM）')
