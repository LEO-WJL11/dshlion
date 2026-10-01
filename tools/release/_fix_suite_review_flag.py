#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给"自己 -jar 起应用"的用例补上 --lionbox.change-review.enabled=false。

【为什么】1.5.3 起"改文件先待审"是**出厂默认**（产品行为）。但工具类用例验的是
"工具能不能把文件改对"，审核开着文件根本不会落盘 —— 一道闸门让五个用例一起红。
`tools/bench/_app.py` 里已经默认关掉了，可这几个用例是**自己拼命令行 `-jar` 起应用**的，
没走 _app.py，于是漏了。

【做法】找到这些用例里那段 arg 列表（含 --lionbox.runtime.prewarm.enabled=false），
在它后面补一行。找不到就报错，不静默跳过。
"""

import io
import glob
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.join(ROOT, 'tools', 'checks')
FLAG = '--lionbox.change-review.enabled=false'
NOTE = ("             # 关掉改动人工审核：这些用例验的是\"工具能不能把文件改对\"，\n"
        "             # 开着审核文件根本不落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）\n"
        "             '%s',\n" % FLAG)

changed = []
for path in sorted(glob.glob(os.path.join(HERE, '_check_*.py'))):
    s = io.open(path, encoding='utf-8').read()
    if FLAG in s:
        continue
    if "'--lionbox.runtime.prewarm.enabled=false'" not in s:
        continue                      # 不走自己起应用的路子（用 _app.py 的已经默认关了）
    # 在 prewarm 那行后面插入（缩进按原文那一行对齐）
    out_lines = []
    inserted = False
    for line in s.split('\n'):
        out_lines.append(line)
        if not inserted and "'--lionbox.runtime.prewarm.enabled=false'" in line:
            indent = line[:len(line) - len(line.lstrip())]
            out_lines.append(indent + '# 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，')
            out_lines.append(indent + '# 开着审核文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py）')
            out_lines.append(indent + "'%s'," % FLAG)
            inserted = True
    if inserted:
        io.open(path, 'w', encoding='utf-8', newline='').write('\n'.join(out_lines))
        changed.append(os.path.basename(path))

print('补了 %d 个用例：%s' % (len(changed), ', '.join(changed) if changed else '(无)'))
sys.exit(0 if changed else 1)
