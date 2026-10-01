#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""补上上一版漏掉的两处：待审改动的 CSS、以及把它挂进 800ms 轮询。

【上一版为什么漏】跳过判断写成了"新内容的第一行已经存在就跳过"，而那两处的第一行
恰好就是锚点本身（`.set-group-title {...}` / `this.pollTimer = ...`），于是永远判断为"已有"。
判断依据要选**新内容里独有的标记**（`.change-box {` / `self.refreshChanges();`），
不能拿锚点自己当判据 —— 这个坑记在这里，免得下次再犯。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'web', 'index.html')

s = io.open(P, encoding='utf-8', newline='').read()
CRLF = '\r\n' in s
if CRLF:
    s = s.replace('\r\n', '\n')
orig = len(s)
ok = True


def ensured(marker, old, new, what):
    """marker 是"新内容里独有的标记"，用它判断要不要插"""
    global s
    if marker in s:
        print('  已经有：%s' % what)
        return True
    if s.count(old) != 1:
        print('  ! 锚点 "%s" 命中 %d 次' % (what, s.count(old)))
        return False
    s = s.replace(old, new, 1)
    print('  已插入：%s' % what)
    return True


ok &= ensured(
    '.change-box {',
    '''        .set-group-title { font-size: 11.5px; color: var(--text-dim); margin: 12px 0 6px; letter-spacing: .5px; }''',
    '''        .set-group-title { font-size: 11.5px; color: var(--text-dim); margin: 12px 0 6px; letter-spacing: .5px; }
        /* 待审改动：贴在输入框上方，AI 改了文件、等人工点头时才出现 */
        .change-box { margin: 0 0 6px; border: 1px solid var(--accent); border-radius: 8px;
                      background: var(--panel-2); padding: 8px 10px; max-height: 260px; overflow: auto; }
        .change-box .chg-head { font-size: 12px; color: var(--text-dim); margin-bottom: 6px; }
        .change-box .chg { border-top: 1px solid var(--border-soft); padding: 6px 0; }
        .change-box .chg-path { font-size: 12px; font-weight: 600; word-break: break-all; }
        .change-box .chg-meta { font-size: 11px; color: var(--text-mute); margin: 2px 0 4px; }
        .change-box pre { max-height: 150px; overflow: auto; font-size: 11px; margin: 4px 0;
                          background: var(--panel); border: 1px solid var(--border-soft);
                          border-radius: 6px; padding: 6px; }
        .change-box .add { color: #3fb950; }
        .change-box .del { color: #f85149; }''',
    '待审改动样式')

ok &= ensured(
    'self.refreshChanges();',
    '''        this.pollTimer = setInterval(function() { self.pollEvents(); }, 800);''',
    '''        this.pollTimer = setInterval(function() {
            self.pollEvents();
            // 顺手带上这两个：待审改动要尽快让用户看到（模型那边可能正等着），
            // 上下文窗口会被 AI 自己改，界面上得跟着变。都复用这一个节拍，不另开定时器。
            self.refreshChanges();
            self.refreshContextChip();
        }, 800);''',
    '轮询挂载')

if CRLF:
    s = s.replace('\n', '\r\n')
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('web/index.html：%d → %d 字符；结果=%s' % (orig, len(s), 'OK' if ok else 'FAIL'))
sys.exit(0 if ok else 1)
