#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""修 iss：IconFilename 只能加在 [Icons] 段里。

上一版脚本按"行首是 Name:"就加，结果把 [Languages] 那行也加了
（ISCC 报 `Unrecognized parameter name "IconFilename"`）。
这里改成按**段(section)**判断：不在 [Icons] 段里的错误后缀一律去掉，
[Icons] 段里的每一行 Name: 都补上。
"""

import io, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
p = os.path.join(ROOT, 'installer', 'LionBox.iss')
s = io.open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
lines = (s.replace('\r\n', '\n') if crlf else s).split('\n')

TAIL = '; IconFilename: "{app}\\lionbox.ico"'
section = ''
fixed, added, removed = 0, 0, 0
out = []
for l in lines:
    st = l.strip()
    if st.startswith('[') and st.endswith(']'):
        section = st
    if TAIL in l:
        if section != '[Icons]':
            l = l.replace(TAIL, '').rstrip()
            removed += 1
    elif section == '[Icons]' and st.startswith('Name:') and 'IconFilename' not in l:
        l = l.rstrip() + TAIL
        added += 1
    out.append(l)

t = '\n'.join(out)
if crlf:
    t = t.replace('\n', '\r\n')
io.open(p, 'w', encoding='utf-8', newline='').write(t)
print('去掉错误位置的 IconFilename: %d 行' % removed)
print('[Icons] 里补上 IconFilename: %d 行' % added)
