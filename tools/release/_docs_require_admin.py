#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""README/使用说明 必须写清：安装包要以**管理员身份运行**，否则会报错。

用户的原话："必须在 readme 写，就是必须得管理员身份运行，不然会报错。"

原来 README 里我写的是"不需要管理员权限"，与实际情况相反 —— 这类错误最坑人：
用户照着说明双击，报权限错，然后不知道该干嘛。改三处：

  1. README.md 的安装步骤：第 1 步就是"右键 → 以管理员身份运行"，并写清不这样的后果；
  2. dist/使用说明.md（安装包里那份，用户装完最先看到）：同样写进最前面那节；
  3. docs/安装包清单.md：安装方式那一行补上"以管理员身份运行"。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

OLD_STEPS = '''**怎么装**：

1. 双击 `LionBox-Setup-1.5.4.exe`；
2. 如果 Windows 弹出蓝色的"已保护你的电脑"（SmartScreen）：点 **更多信息 → 仍要运行**
   —— 安装包没有买代码签名，这一步是正常的，不是有毒；
3. 一路下一步（**不需要管理员权限**，装到当前用户目录）；'''

NEW_STEPS = '''**怎么装**：

1. **右键安装包 → 「以管理员身份运行」** —— 这一步是必须的：
   不加管理员权限安装会**报权限错误**（写文件/装 VS Code 插件那几步会失败）；
2. 如果 Windows 弹出蓝色的"已保护你的电脑"（SmartScreen）：点 **更多信息 → 仍要运行**
   —— 安装包没有买代码签名，这一步是正常的，不是有毒；
3. 接着弹 UAC 询问时点「是」，然后一路下一步；'''

GUIDE_ADD = '''> **装之前先看这一条**：安装包要**以管理员身份运行** ——
> 右键 `LionBox-Setup-*.exe` → 「以管理员身份运行」，弹出 UAC 时点「是」。
> 直接双击会因为权限不足**报错**（装文件、装 VS Code 插件那几步会失败）。

'''

CHECKLIST_OLD = '| `LionBox-Setup-1.5.4.exe` |'
CHECKLIST_NOTE = '''> **安装方式**：右键 → **以管理员身份运行**（必须；直接双击会因权限不足报错）。
> 弹 UAC 时点「是」，之后一路下一步。

'''


def patch(rel, pairs, must=True):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        print('跳过（不存在）：%s' % rel)
        return False
    s = io.open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    body = s.replace('\r\n', '\n') if crlf else s
    hits = 0
    for old, new in pairs:
        if old in body:
            body = body.replace(old, new)
            hits += 1
        elif must:
            print('  ! %s 里没找到锚点：%s' % (rel, old[:50].replace('\n', '⏎')))
    if crlf:
        body = body.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(body)
    print('%s：替换 %d 处' % (rel, hits))
    return hits > 0


ok = True
ok &= patch('README.md', [(OLD_STEPS, NEW_STEPS)])

# 使用说明：插在文件最前面（这一版最重要的三件事之前）
p = os.path.join(ROOT, 'dist', '使用说明.md')
if os.path.isfile(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    if '管理员身份运行' in s:
        print('dist/使用说明.md：已写过管理员这一条')
    else:
        crlf = '\r\n' in s
        add = GUIDE_ADD.replace('\n', '\r\n') if crlf else GUIDE_ADD
        io.open(p, 'w', encoding='utf-8', newline='').write(add + s)
        print('dist/使用说明.md：已在最前面加上"以管理员身份运行"')

# 安装包清单
p = os.path.join(ROOT, 'docs', '安装包清单.md')
if os.path.isfile(p):
    s = io.open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    body = s.replace('\r\n', '\n') if crlf else s
    if '管理员身份运行' in body:
        print('docs/安装包清单.md：已写过')
    else:
        anchor = '## 一套包，怎么用'
        if anchor in body:
            body = body.replace(anchor, CHECKLIST_NOTE + anchor, 1)
        else:
            body = CHECKLIST_NOTE + body
        if crlf:
            body = body.replace('\n', '\r\n')
        io.open(p, 'w', encoding='utf-8', newline='').write(body)
        print('docs/安装包清单.md：已加安装方式说明')

sys.exit(0 if ok else 1)
