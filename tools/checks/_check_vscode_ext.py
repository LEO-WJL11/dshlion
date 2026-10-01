#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""VS Code 插件自检：清单结构 + 内联面板脚本的语法。

【为什么要单独测】扩展跑在 VS Code 里，这个环境没法真开一个 VS Code；
但"清单写错了（视图挂错地方）"和"面板里的内联 JS 有语法错"这两类问题，
不用打开编辑器也能验 —— 而且这两类恰恰是最容易悄悄坏掉的。

验的东西：
  1. package.json 里视图容器挂在 **secondarySidebar**（用户要的"最右边那一栏"）；
  2. 视图 = webview 类型，id=lionbox.agent；
  3. 五个命令都在，且 extension.js 里都 registerCommand 了；
  4. activationEvents 认 onView:lionbox.agent（打开面板就激活）；
  5. 图标文件在（viewsContainers 的 icon 路径错了 VS Code 会拒绝加载整个容器）；
  6. 面板内联 <script> 抽出来交给 node --check（模板字符串里写 JS 最容易出语法错）。

用法：python tools/checks/_check_vscode_ext.py
"""

import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXT = os.path.join(ROOT, 'extensions', 'vscode')
failed = []

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def check(label, ok, detail=''):
    print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
    if not ok:
        failed.append(label)
    return ok


def main():
    pkg_path = os.path.join(EXT, 'package.json')
    src_path = os.path.join(EXT, 'src', 'extension.js')
    check('package.json 在', os.path.isfile(pkg_path))
    check('extension.js 在', os.path.isfile(src_path))
    if failed:
        return 1

    pkg = json.load(io.open(pkg_path, encoding='utf-8'))
    src = io.open(src_path, encoding='utf-8').read()
    c = pkg.get('contributes', {})

    # 1) 右侧栏
    containers = c.get('viewsContainers', {})
    check('★ 视图容器挂在 secondarySidebar（就是右边最右那一栏）',
          'secondarySidebar' in containers, list(containers.keys()))
    bar = (containers.get('secondarySidebar') or [{}])[0]
    check('容器 id / 标题', bar.get('id') == 'lionboxAgentBar' and bar.get('title'),
          '%s / %s' % (bar.get('id'), bar.get('title')))
    icon = bar.get('icon') or ''
    check('★ 容器图标文件真的在（路径错会让整个容器加载失败）',
          bool(icon) and os.path.isfile(os.path.join(EXT, icon.replace('/', os.sep))), icon)

    # 2) 视图
    views = c.get('views', {})
    group = views.get(bar.get('id')) or []
    check('★ 视图是 webview 类型且挂在容器下',
          group and group[0].get('type') == 'webview' and group[0].get('id') == 'lionbox.agent',
          json.dumps(group, ensure_ascii=False)[:120])

    # 3) 命令
    cmds = [x.get('command') for x in c.get('commands', [])]
    for need in ('lionbox.openAgent', 'lionbox.newSession', 'lionbox.referenceFile',
                 'lionbox.openWebUI', 'lionbox.reload'):
        check('命令在清单里：' + need, need in cmds)
        check('命令在代码里注册：' + need, ("registerCommand('%s'" % need) in src
              or ('registerCommand("%s"' % need) in src)

    # 4) 激活事件
    check('★ 打开面板就激活（onView:lionbox.agent）',
          'onView:lionbox.agent' in (pkg.get('activationEvents') or []),
          str(pkg.get('activationEvents')))
    check('视图 id 与激活事件一致（写错就是"面板永远空白"）',
          'lionbox.agent' in src and 'registerWebviewViewProvider' in src)

    # 5) 关键能力在代码里
    for label, needle in (
        ('★ 工作区来自"当前打开的文件夹"', 'workspaceFolders'),
        ('★ 工作区交给后端（建/复用 workspace）', "'/api/workspaces'"),
        ('★ 对话走后端 /api/chat', "'/api/chat'"),
        ('★ @ 引用文件（只读那一个文件进上下文）', '@file:'),
        ('★ 通过改动 = POST /approve', '/approve'),
        ('★ 打回改动 = POST /reject（带理由）', '/reject'),
        ('打回理由用输入框收', 'showInputBox'),
    ):
        check(label, needle in src, needle)

    # 6) 内联脚本语法
    m = re.search(r'<script>(.*?)</script>', src, re.S)
    if not check('面板里有内联脚本', bool(m)):
        return 1
    tmp = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_panel_check.js')
    io.open(tmp, 'w', encoding='utf-8').write(m.group(1))
    node = subprocess.run(['node', '--check', tmp], capture_output=True, text=True, errors='replace')
    check('★ 面板内联 JS 语法正确（模板串里写 JS 最容易坏）', node.returncode == 0,
          (node.stderr or '')[-300:])
    try:
        os.remove(tmp)
    except OSError:
        pass

    node2 = subprocess.run(['node', '--check', src_path], capture_output=True, text=True, errors='replace')
    check('extension.js 语法正确', node2.returncode == 0, (node2.stderr or '')[-300:])

    print()
    print('结果：%s' % ('全部通过' if not failed else '失败 %d 项：%s' % (len(failed), failed)))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
