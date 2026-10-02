#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""切分 + **当场校验**（同一次运行里拼回来比 sha256），避免跨命令比错文件。

上一版校验返回 False，最可能的原因是"两次运行各自 glob 出来的 exe 不是同一个"
（1.5.23 和 1.5.9 这种字符串排序会把 1.5.9 排后面）。所以这里：
  · 用 os.path.getmtime 选**最新的** exe，而不是字符串排序；
  · 切完立刻在内存里拼回来比对，一致才算成功，不一致直接报错退出。
"""

import glob, hashlib, io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join(ROOT, 'installer', 'release')

exes = glob.glob(os.path.join(REL, 'LionBox-Setup-*.exe'))
if not exes:
    sys.exit('没有安装包')
exe = max(exes, key=os.path.getmtime)          # 最新那个，别用字符串排序
name = os.path.basename(exe)
print('目标安装包:', name, '%.1f MB' % (os.path.getsize(exe) / 1024 / 1024))

for f in glob.glob(os.path.join(REL, 'LionBox-Setup-*.exe.part*')):
    os.remove(f)
    print('  清掉旧分卷:', os.path.basename(f))

CHUNK = 90 * 1024 * 1024
data = open(exe, 'rb').read()
orig = hashlib.sha256(data).hexdigest().upper()
parts = []
for i in range(0, len(data), CHUNK):
    p = '%s.part%03d' % (exe, i // CHUNK + 1)
    open(p, 'wb').write(data[i:i + CHUNK])
    parts.append(p)

# 当场校验
h = hashlib.sha256()
for p in parts:
    h.update(open(p, 'rb').read())
same = h.hexdigest().upper() == orig
print('分卷数:', len(parts), '｜ 当场拼回一致:', same)
for p in parts:
    print('  %-42s %5.1f MB' % (os.path.basename(p), os.path.getsize(p) / 1024 / 1024))
if not same:
    sys.exit('校验失败：分卷拼回来跟原文件不一致，不许发布')

lines = ['@echo off',
         'rem Join the LionBox installer volumes back into one exe, then check the hash.',
         'rem Put every part file and this .bat in the same folder, then double-click.',
         'setlocal', 'cd /d "%~dp0"', 'set OUT=' + name,
         'if exist "%OUT%" del "%OUT%"',
         'copy /b ' + '+'.join('"%s"' % os.path.basename(p) for p in parts) + ' "%OUT%" >nul',
         'echo Done: %OUT%', 'echo.',
         'echo Verify with:  certutil -hashfile "%OUT%" SHA256',
         'echo Expected  :  ' + orig, 'echo.',
         'echo Run %OUT% AS ADMINISTRATOR (right click, Run as administrator).',
         'pause']
io.open(os.path.join(REL, 'join-installer.bat'), 'w', encoding='ascii', newline='\r\n').write('\r\n'.join(lines) + '\r\n')
print('join-installer.bat 已写，内嵌 sha256:', orig)
