#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把「先说清楚这个项目不吹什么」那一节从 README 开头挪到「已知限制」前面。

【为什么要挪】第一次来的人（包括用户自己）在 GitHub 上只看得到第一屏：
"不吹什么"当然要写，但它不是参观者最需要的东西 —— **下载在哪、怎么装**才是。
所以顺序改成：介绍 → 下载与安装 → 仓库地图 → 功能 → …… → 不吹什么 + 已知限制。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'README.md')

BLOCK_START = '## 先说清楚这个项目不吹什么'
BLOCK_END = '## ⬇️ 下载与安装'
TARGET = '## 已知限制'

s = io.open(P, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
body = s.replace('\r\n', '\n') if crlf else s

a = body.find(BLOCK_START)
b = body.find(BLOCK_END)
if a < 0 or b < 0 or b < a:
    print('! 找不到那两节的锚点（a=%d b=%d）' % (a, b))
    sys.exit(1)
block = body[a:b]                       # 含结尾空行
body = body[:a] + body[b:]

t = body.find(TARGET)
if t < 0:
    print('! 找不到「已知限制」那一节')
    sys.exit(1)
body = body[:t] + block + body[t:]

if crlf:
    body = body.replace('\n', '\r\n')
io.open(P, 'w', encoding='utf-8', newline='').write(body)

# 打印一下现在的章节顺序，方便一眼确认
order = [l.strip() for l in body.replace('\r\n', '\n').split('\n') if l.startswith('## ')]
print('README 章节顺序：')
for i, x in enumerate(order, 1):
    print('  %2d. %s' % (i, x))
