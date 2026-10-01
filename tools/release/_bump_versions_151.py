#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把三个包的版本号提到 1.5.1（用 Python 改，不用 PowerShell）。

【为什么要专门写个文件】这台机器上 PowerShell 的 Get-Content/Set-Content 会按系统 ANSI
码页（GBK）读写文件，用它改带中文的 UTF-8 文件会把中文整段变成乱码 —— 这一轮已经栽过两次
（web/index.html 一次、package.json 一次），而且 Set-Content -Encoding UTF8 还会顺手加 BOM，
JSON 解析器直接报 "not a valid JSON"。
"""

import io
import json
import sys

TARGET = '1.5.1'


def bump(path, old, new):
    raw = io.open(path, 'rb').read()
    txt = raw.decode('utf-8')
    if new in txt:
        print('已经是 %s，跳过：%s' % (TARGET, path))
        return True
    if old not in txt:
        print('FAIL 找不到 %r：%s' % (old, path))
        return False
    io.open(path, 'wb').write(txt.replace(old, new).encode('utf-8'))
    print('已改 %s' % path)
    return True


ok = True
ok &= bump('desktop/electron/package.json', '"version": "1.5.0"', '"version": "%s"' % TARGET)
ok &= bump('extensions/vscode/package.json', '"version": "1.5.0"', '"version": "%s"' % TARGET)
ok &= bump('extensions/jetbrains/gradle.properties', 'pluginVersion = 1.5.0',
           'pluginVersion = %s' % TARGET)

# 改完必须验：JSON 能解析、中文没乱、没有 BOM
for p in ('desktop/electron/package.json', 'extensions/vscode/package.json'):
    raw = io.open(p, 'rb').read()
    if raw[:3] == b'\xef\xbb\xbf':
        print('FAIL 有 BOM：%s' % p)
        ok = False
    try:
        d = json.loads(raw.decode('utf-8'))
    except Exception as e:
        print('FAIL JSON 解析不了 %s：%s' % (p, e))
        ok = False
        continue
    bad = '妗' in d.get('description', '') or '锛' in d.get('description', '')
    print('%-42s version=%s 乱码=%s' % (p, d.get('version'), '是' if bad else '否'))
    ok &= (d.get('version') == TARGET) and not bad

g = io.open('extensions/jetbrains/gradle.properties', encoding='utf-8').read()
print('gradle.properties 里 pluginVersion = %s，中文注释还在：%s'
      % (TARGET, '是' if '目标 IntelliJ Platform' in g else '否'))
ok &= ('pluginVersion = %s' % TARGET) in g and '目标 IntelliJ Platform' in g

sys.exit(0 if ok else 1)
