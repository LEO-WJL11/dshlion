#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 LionBoxDesktop.iss 里注册表路径中的 GUID 花括号转义成 {{...}}。

【为什么必须转义】Inno Setup 会把字符串里的 `{...}` 当**常量**去展开。
注册表路径 `SOFTWARE\\...\\Clients\\{F3017226-...}` 里的 GUID 被当成常量名 →
运行期"未知常量" → 安装程序**启动即退出（exit=1、连日志都不写、一个文件都不装）**。
这个坑很难猜：编译期不报错，只在运行期炸，而且静默模式下什么都不显示。
（[Setup] 段的 AppId 用 {{...}} 本来就是同一个道理，那边一开始就写对了。）
"""

import io
import re
import sys

P = 'installer/LionBoxDesktop.iss'
GUIDS = ['F3017226-FE2A-4295-8BDF-00C3A9A7E4C5', '56EB18F8-B008-4CBD-B6D2-8C97FE7E9062']

s = io.open(P, encoding='utf-8', newline='').read()
changed = 0
for g in GUIDS:
    # 已经是 {{...}} 的别再套一层
    s, n = re.subn(r'(?<!\{)\{' + re.escape(g) + r'\}(?!\})', '{{' + g + '}}', s)
    changed += n

io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('转义了 %d 处 GUID 花括号' % changed)
for line in s.split('\n'):
    if 'RegQueryStringValue' in line:
        print('  ' + line.strip()[:120])
sys.exit(0 if changed else 1)
