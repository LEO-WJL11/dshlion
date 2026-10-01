#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 installer/LionBox.iss 的版本历史补一行 1.5.2（放在 1.5.1 那条前面）。"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'installer', 'LionBox.iss')

s = io.open(P, encoding='utf-8', newline='').read()
NL = '\r\n' if '\r\n' in s else '\n'

if '1.5.2：' in s:
    print('已有 1.5.2 记录，跳过')
    sys.exit(0)

anchor = '; 1.5.1：'
i = s.find(anchor)
if i < 0:
    raise SystemExit('找不到 1.5.1 那行，先看一眼')

lines = [
    '; 1.5.2：设置页签从 8 个并到 5 个 —— 「插件 / 插件参数 / 技能 / 审批策略」合成一个「插件管理」',
    ';         （每个插件的开关和它自己的参数挨在一起；技能进 SKILL 分组；审批策略挪到授权审查插件下面；',
    ';          老页名 plugins/pluginparams/skills/approvals 仍能落到插件管理）。四个包重出重验。',
]
s = s[:i] + NL.join(lines) + NL + s[i:]
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('已补 1.5.2 版本历史（3 行）')
