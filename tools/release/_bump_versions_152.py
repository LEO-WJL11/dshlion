#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把版本号从 1.5.1 提到 1.5.2（改一个地方漏一个，四个包版本号就对不上了）。

【为什么用 Python 改】这台机器上 PowerShell 的 Get-Content/Set-Content 按系统 ANSI 码页
（GBK）读写，改带中文的 UTF-8 文件会把中文整段变成乱码；Set-Content -Encoding UTF8 还会加
BOM（JSON/XML 解析器直接报错）。这一轮已经栽过两次，所以版本号一律用 Python 改，改完验三件事：
JSON 能解析、中文没乱、没有 BOM。

覆盖的地方（1.5.1 → 1.5.2）：
  pom.xml                          后端 jar
  installer/LionBox.iss            主安装包（AppVersion + 版本历史里那行）
  installer/LionBoxDesktop.iss     桌面版安装包
  desktop/tauri/package.json       桌面工程
  desktop/tauri/src-tauri/Cargo.toml
  desktop/tauri/src-tauri/tauri.conf.json
  extensions/vscode/package.json   VS Code 插件
  extensions/jetbrains/gradle.properties   JetBrains 插件
"""

import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OLD = '1.5.1'
NEW = '1.5.2'

# (文件, 旧片段, 新片段) —— 一个文件可以有多处
# 注意：pom.xml 的版本一直是 1.0.0-SNAPSHOT（后端 jar 不跟发布号走），不用改；
# 两个 .iss 的 OutputBaseFilename 用的是 {#AppVersion}，改 #define 就自动跟着变。
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
    raw = io.open(p, 'rb').read()
    txt = raw.decode('utf-8')
    if new in txt:
        print('已是 %s，跳过：%s' % (NEW, rel))
        continue
    if old not in txt:
        print('FAIL 找不到 %r：%s' % (old, rel))
        ok = False
        continue
    io.open(p, 'wb').write(txt.replace(old, new).encode('utf-8'))
    print('已改 %s：%s' % (rel, old.replace(OLD, '…') if OLD not in old else old))

# 改完必须验：JSON 能解析、中文没乱、没有 BOM
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
    ver = d.get('version')
    print('%-46s version=%s' % (rel, ver))
    if ver != NEW:
        print('FAIL 版本号不对：%s' % rel)
        ok = False

# 中文完整性抽查：这几处都必须还在
sanity = [
    ('installer/LionBoxDesktop.iss', 'WebView2'),
    ('desktop/tauri/src-tauri/src/main.rs', '本地服务'),
    ('extensions/vscode/package.json', 'LionBox'),
    ('installer/LionBox.iss', '1.2.0'),
]
for rel, needle in sanity:
    txt = io.open(os.path.join(ROOT, rel), encoding='utf-8', errors='replace').read()
    good = needle in txt
    print('%-46s 含「%s」：%s' % (rel, needle, '是' if good else '否'))
    ok &= good

print()
print('结果：%s（%s → %s）' % ('全部通过' if ok else '有失败项', OLD, NEW))
sys.exit(0 if ok else 1)
