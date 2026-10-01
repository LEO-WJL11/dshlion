#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把版本号从 1.5.2 提到 1.5.3。

【为什么用 Python 改】这台机器上 PowerShell 的 Get-Content/Set-Content 按系统 ANSI 码页
（GBK）读写，改带中文的 UTF-8 文件会把中文整段变成乱码；Set-Content -Encoding UTF8 还会加
BOM（JSON/XML 解析器直接报错）。这一轮已经栽过两次，所以版本号一律用 Python 改。

【为什么每次都复制一份而不是参数化】故意的：发布脚本要能独立回看"那一版到底改了什么"，
参数化之后就没有"1.5.3 那一刻的脚本"了。改动小、复制成本低，值得。
覆盖的地方见 EDITS（两个 iss 的 AppVersion 是唯一的版本来源，OutputBaseFilename 用的是
{#AppVersion}，自动跟着变；pom.xml 一直是 1.0.0-SNAPSHOT，不跟发布号走）。
"""

import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OLD = '1.5.2'
NEW = '1.5.3'

EDITS = [
    ('installer/LionBox.iss', '#define AppVersion     "%s"' % OLD, '#define AppVersion     "%s"' % NEW),
    ('installer/LionBoxDesktop.iss', '#define AppVersion     "%s"' % OLD,
     '#define AppVersion     "%s"' % NEW),
    ('desktop/tauri/package.json', '"version": "%s"' % OLD, '"version": "%s"' % NEW),
    ('desktop/tauri/src-tauri/Cargo.toml', 'version = "%s"' % OLD, 'version = "%s"' % NEW),
    ('desktop/tauri/src-tauri/tauri.conf.json', '"version": "%s"' % OLD, '"version": "%s"' % NEW),
    ('extensions/vscode/package.json', '"version": "%s"' % OLD, '"version": "%s"' % NEW),
    ('extensions/jetbrains/gradle.properties', 'pluginVersion = %s' % OLD, 'pluginVersion = %s' % NEW),
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

# 改完验三件事：JSON 能解析、中文没乱、没有 BOM
for rel in ('desktop/tauri/package.json', 'desktop/tauri/src-tauri/tauri.conf.json',
            'extensions/vscode/package.json'):
    p = os.path.join(ROOT, rel)
    raw = io.open(p, 'rb').read()
    if raw[:3] == b'\xef\xbb\xbf':
        print('FAIL 有 BOM：%s' % rel)
        ok = False
    try:
        d = json.loads(raw.decode('utf-8'))
    except Exception as e:
        print('FAIL JSON 解析不了 %s：%s' % (rel, e))
        ok = False
        continue
    print('%-46s version=%s' % (rel, d.get('version')))
    if d.get('version') != NEW:
        print('FAIL 版本号不对：%s' % rel)
        ok = False

for rel, needle in (('installer/LionBoxDesktop.iss', 'WebView2'),
                    ('desktop/tauri/src-tauri/src/main.rs', '本地服务'),
                    ('extensions/vscode/package.json', 'LionBox'),
                    ('extensions/vscode/src/extension.js', '右侧栏'),
                    ('installer/LionBox.iss', '1.2.0')):
    txt = io.open(os.path.join(ROOT, rel), encoding='utf-8', errors='replace').read()
    good = needle in txt
    print('%-46s 含「%s」：%s' % (rel, needle, '是' if good else '否'))
    ok &= good

print()
print('结果：%s（%s → %s）' % ('全部通过' if ok else '有失败项', OLD, NEW))
sys.exit(0 if ok else 1)
