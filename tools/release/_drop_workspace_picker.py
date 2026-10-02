#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""WebUI：删掉"选择工作区"那一整套（用户定稿：工作区 = 左边 VS Code 打开的文件夹）。

删的内容：
  · `// ====== 工作区 ======` 到 `selectWs` 之间的 `pickWorkspace`（弹系统文件夹选择框）
    和 `openWorkspaceFallbackModal`（常用目录兜底弹窗）；
  · `.ws-pick` 两条样式（每个对话右边的"换工作区"下拉已经在上一步删了）。
保留 `selectWs`：它现在只被内部逻辑用（把某个路径注册成工作区），界面上不再有入口。
写完强制保留原来的换行风格（这个文件是 CRLF）。
"""

import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
p = os.path.join(ROOT, 'web', 'index.html')
raw = io.open(p, 'rb').read()
bom = raw[:3] == b'\xef\xbb\xbf'
t = raw.decode('utf-8-sig')
crlf = '\r\n' in t
t = t.replace('\r\n', '\n')

start = t.find('    // ====== 工作区 ======')
end = t.find('    selectWs: function(path) {')
if start < 0 or end < 0 or end < start:
    print('! 锚点没找到，先看一眼文件结构'); sys.exit(1)

removed = t[start:end]
t = (t[:start]
     + '    // 工作区不再由用户选择：adoptWorkspace 直接用左边 VS Code 打开的那个文件夹。\n\n'
     + t[end:])

t = re.sub(r'\n\s*\.ws-pick \{[^}]*\}', '', t)
t = re.sub(r'\n\s*\.ws-pick:hover \{[^}]*\}', '', t)

out = (b'\xef\xbb\xbf' if bom else b'') + (t.replace('\n', '\r\n') if crlf else t).encode('utf-8')
io.open(p, 'wb').write(out)
print('删掉了 %d 行工作区选择代码（pickWorkspace + 常用目录弹窗）' % removed.count('\n'))
print('.ws-pick 样式残留:', t.count('.ws-pick'))
print('还有没有 pickWorkspace 调用:', t.count('pickWorkspace'))
