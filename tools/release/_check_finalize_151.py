#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""校验 _finalize_151.py 生成得对不对（PowerShell 里内联 python 的引号太容易崩，写成文件）。"""

import ast
import io
import sys

p = 'tools/release/_finalize_151.py'
s = io.open(p, encoding='utf-8').read()
checks = [
    ("版本号是 1.5.1", "VER = '1.5.1'" in s),
    ("有第 40 轮正文", '第 40 轮：1.5.1' in s),
    ("旧的 1.5.0 正文已换掉", '第 39 轮：1.5.0' not in s),
    ("包内反查含 maxToolsPerRound", 'maxToolsPerRound' in s),
    ("轮次判定改成 40", "u'第 40 轮' in t2" in s),
]
ok = True
for name, good in checks:
    print(('OK   ' if good else 'FAIL ') + name)
    ok = ok and good
try:
    ast.parse(s)
    print('OK   语法正确')
except SyntaxError as e:
    print('FAIL 语法错误: %s' % e)
    ok = False
sys.exit(0 if ok else 1)
