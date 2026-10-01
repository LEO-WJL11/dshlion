#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给"自己 -jar 起应用"的用例补 --lionbox.change-review.enabled=false（正确版）。

【第一版插错了地方，27 个用例瞬间全红】我按"在含 prewarm 的那行之后插入"来做，
可那一行恰好就是参数列表的最后一项加 `],`：
    proc = subprocess.Popen([... '--lionbox.runtime.prewarm.enabled=false'],
                            ^^^^ 列表在这行就闭合了
插到它后面，那个字符串就变成了 Popen 的第二个位置参数 —— 语法没错、运行必炸，
所以 27 个用例 0.1 秒就"失败"了。正确做法是插在**最后一项之前**，保住 `],`。

【为什么这些用例需要关掉审核】1.5.3 起"改文件先待审"是出厂默认（产品行为）。
工具类用例验的是"工具能不能把文件改对"，审核开着文件根本不会落盘。
tools/bench/_app.py 里已经默认关掉了，但这批用例是自己拼命令行 `-jar` 起应用的。
"""

import glob
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.join(ROOT, 'tools', 'checks')
FLAG = "'--lionbox.change-review.enabled=false',"
PATTERNS = ("'--lionbox.runtime.prewarm.enabled=false'",
            '"--lionbox.runtime.prewarm.enabled=false"')

changed = []
for path in sorted(glob.glob(os.path.join(HERE, '_check_*.py'))):
    s = io.open(path, encoding='utf-8').read()
    if 'change-review.enabled' in s:
        continue
    out = []
    inserted = False
    for line in s.split('\n'):
        if not inserted and any(p in line for p in PATTERNS):
            indent = line[:len(line) - len(line.lstrip())]
            out.append(indent + '# 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，开着审核')
            out.append(indent + '# 文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）')
            out.append(indent + FLAG)
            inserted = True
        out.append(line)
    if inserted:
        io.open(path, 'w', encoding='utf-8', newline='').write('\n'.join(out))
        changed.append(os.path.basename(path))

print('补了 %d 个用例' % len(changed))
for n in changed:
    print('  ' + n)
sys.exit(0 if changed else 1)
