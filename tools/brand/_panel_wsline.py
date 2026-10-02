#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""切页签时把"品牌状态药丸"和"工作区行"一起隐藏。

【为什么单独写文件】刚才用 PowerShell 内联 python -c 干这事，引号被 PS 吃掉、脚本直接语法错，
改动一点没生效（这坑这次会话里已经踩了三次）。写成文件就绕开了。
"""

import io, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
p = os.path.join(ROOT, 'extensions', 'vscode', 'src', 'extension.js')
t = io.open(p, encoding='utf-8').read()
n = 0

if 'id="wsline"' not in t:
    t = t.replace('<div class="wsline">工作区', '<div class="wsline" id="wsline">工作区', 1)
    n += 1

before = "['status', 'ws', 'input']"
after = "['statusPill', 'wsline', 'input']"
if before in t:
    t = t.replace(before, after, 1)
    n += 1

io.open(p, 'w', encoding='utf-8', newline='').write(t)
print('改了 %d 处' % n)
print('wsline id:', 'id="wsline"' in t)
print('showTab 列表:', after in t)
