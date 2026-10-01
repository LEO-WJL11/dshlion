#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""收尾两处：① diff 配色改用主题变量（用例要求不许有硬编码色）；② 把待审改动真的挂进轮询。

【第二次又踩同一个坑】上一次用 `self.refreshChanges();` 当"是否已插入"的判据，
可 approveChange/rejectChange 里本来就有这句 —— 于是又误判成"已有"。
这次判据用**只有轮询块才会出现**的组合：`self.refreshContextChip();\n        }, 800);`
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
ok = True

# ① 颜色变量：--ok / --err（两个主题里都定义了，切换主题时才对得上）
if '.change-box .add { color: #3fb950; }' in s:
    s = s.replace('.change-box .add { color: #3fb950; }',
                  '.change-box .add { color: var(--ok); }', 1)
    s = s.replace('.change-box .del { color: #f85149; }',
                  '.change-box .del { color: var(--err); }', 1)
    print('  已把 diff 配色改成主题变量（--ok / --err）')
elif '.change-box .add { color: var(--ok); }' in s:
    print('  配色已经是主题变量')
else:
    print('  ! 找不到 diff 配色那两行')
    ok = False

# ② 轮询挂载：判据用只有轮询块才有的组合
POLL_MARK = 'self.refreshContextChip();\n        }, 800);'
if POLL_MARK.replace('\n', '\n') in s:
    print('  轮询已经挂好了')
else:
    old = '        this.pollTimer = setInterval(function() { self.pollEvents(); }, 800);'
    if s.count(old) != 1:
        print('  ! 轮询那行命中 %d 次' % s.count(old))
        ok = False
    else:
        s = s.replace(old, '''        this.pollTimer = setInterval(function() {
            self.pollEvents();
            // 顺手带上这两个：待审改动要尽快让用户看到（模型那边可能正等着），
            // 上下文窗口会被 AI 自己改，界面上得跟着变。都复用这一个节拍，不另开定时器。
            self.refreshChanges();
            self.refreshContextChip();
        }, 800);''', 1)
        print('  已把待审改动 + 窗口指示挂进 800ms 轮询')

if CRLF:
    s = s.replace('\n', '\r\n')
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('结果=%s' % ('OK' if ok else 'FAIL'))
sys.exit(0 if ok else 1)
