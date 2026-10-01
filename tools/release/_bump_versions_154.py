#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把版本号从 1.5.3 提到 1.5.4。

【这一版起只剩两处版本号】桌面版（desktop/tauri）和 JetBrains 插件已经废弃删除，
就剩主安装包（installer/LionBox.iss）和 VS Code 插件（extensions/vscode/package.json）。
少两处，就少两处漏改的机会。

【为什么每次都写一份而不是参数化】故意的：发布脚本要能独立回看"那一版到底改了什么"。
"""

import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OLD = '1.5.3'
NEW = '1.5.4'

EDITS = [
    ('installer/LionBox.iss', '#define AppVersion     "%s"' % OLD, '#define AppVersion     "%s"' % NEW),
    ('extensions/vscode/package.json', '"version": "%s"' % OLD, '"version": "%s"' % NEW),
]

ok = True
for rel, old, new in EDITS:
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        print('FAIL 文件不在：%s' % rel)
        ok = False
        continue
    txt = io.open(p, 'rb').read().decode('utf-8')
    if new in txt:
        print('已是 %s，跳过：%s' % (NEW, rel))
        continue
    if old not in txt:
        print('FAIL 找不到 %r：%s' % (old, rel))
        ok = False
        continue
    io.open(p, 'wb').write(txt.replace(old, new).encode('utf-8'))
    print('已改 %s' % rel)

for rel in ('extensions/vscode/package.json',):
    raw = io.open(os.path.join(ROOT, rel), 'rb').read()
    if raw[:3] == b'\xef\xbb\xbf':
        print('FAIL 有 BOM：%s' % rel)
        ok = False
    d = json.loads(raw.decode('utf-8'))
    print('%-46s version=%s' % (rel, d.get('version')))
    ok &= d.get('version') == NEW

for rel, needle in (('installer/LionBox.iss', 'VS Code 插件'),
                    ('installer/安装VS Code插件.bat', 'lioncode.lionbox'),
                    ('extensions/vscode/src/extension.js', '右侧栏')):
    txt = io.open(os.path.join(ROOT, rel), encoding='utf-8', errors='replace').read()
    good = needle in txt
    print('%-46s 含「%s」：%s' % (rel, needle, '是' if good else '否'))
    ok &= good

# 废弃的版本必须真的不在了（否则"其他版本都不要了"是空话）
for gone in ('installer/LionBoxDesktop.iss', 'desktop/tauri', 'extensions/jetbrains'):
    p = os.path.join(ROOT, gone.replace('/', os.sep))
    exists = os.path.exists(p)
    print('%-46s 已删除：%s' % (gone, '否（还在！）' if exists else '是'))
    ok &= not exists

print()
print('结果：%s（%s → %s）' % ('全部通过' if ok else '有失败项', OLD, NEW))
sys.exit(0 if ok else 1)
