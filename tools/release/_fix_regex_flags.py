#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 _merge_settings_tabs.py 里三个跨行锚点补上 re.S（少了它 . 不匹配换行，锚点 0 命中）。"""

import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'tools', 'release', '_merge_settings_tabs.py')

s = io.open(P, encoding='utf-8').read()
pairs = [
    ("    'renderPluginsTab')", "    'renderPluginsTab', re.S)"),
    ("    '分组循环')", "    '分组循环', re.S)"),
    ("    'pluginInlineHtml')", "    'pluginInlineHtml', re.S)"),
]
n = 0
for old, new in pairs:
    if old in s:
        s = s.replace(old, new)
        n += 1
io.open(P, 'w', encoding='utf-8').write(s)
print('补了 %d 处 re.S' % n)
if n != 3:
    raise SystemExit('预期补 3 处，实际 %d —— 请检查脚本内容' % n)
