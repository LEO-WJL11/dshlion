#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""修卸载路径的两处毛病（1.5.4 验包时抓出来的）。

【写这个脚本时踩的坑】原来的说明里直接照着抄了 Inno 参数里的那串引号，
而四个连续引号会把 Python 的文档字符串**提前结束**（三个引号是结尾，第四个开始新字符串），
于是后面全被当成普通字符串、\x 之类的转义直接报语法错。教训：文档字符串里别出现连续三引号。
下面用文字描述引号数量，不照抄。

① [UninstallRun] 的参数写法
   原来是 /c 后面跟一对双引号包路径、再跟 uninstall。带参数时 cmd 的引号解析会歪，
   **卸载脚本根本没跑**（实测：%TEMP% 里没有卸载记录文件，插件也还留在 VS Code 里）。
   改成 /c call 加一对双引号包路径，再跟 uninstall —— call 把路径和参数当两个词，稳。

② 卸载残留
   安装时脚本自己写了 vscode-extension\install-result.txt，那不是 Inno 装的文件，
   会把目录撑住（Inno 只删自己装的文件和空目录）。加一条 [UninstallDelete] 显式删掉整个目录。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'installer', 'LionBox.iss')

s = io.open(P, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
if crlf:
    s = s.replace('\r\n', '\n')

OLD_RUN = ('Filename: "{cmd}"; Parameters: "/c """"{app}\\install-vscode-ext.bat"""" uninstall"; '
           'Flags: runhidden waituntilterminated')
NEW_RUN = ('Filename: "{cmd}"; Parameters: "/c call ""{app}\\install-vscode-ext.bat"" uninstall"; '
           'Flags: runhidden waituntilterminated')
if OLD_RUN not in s:
    print('! 找不到旧的 UninstallRun 行（可能已经改过）：')
    print([l for l in s.split('\n') if 'uninstall' in l and 'Filename' in l])
    sys.exit(1)
s = s.replace(OLD_RUN, NEW_RUN, 1)

OLD_DEL = ('[UninstallDelete]\n'
           '; 仅清理运行时生成的日志；用户数据（会话/工作区/配置）保留\n'
           'Type: filesandordirs; Name: "{app}\\logs"')
NEW_DEL = ('[UninstallDelete]\n'
           '; 仅清理运行时生成的日志；用户数据（会话/工作区/配置）保留\n'
           'Type: filesandordirs; Name: "{app}\\logs"\n'
           '; VS Code 插件目录：里面除了我们装的 vsix，还有安装脚本写的 install-result.txt。\n'
           '; 后者不是 Inno 装的，会把这个目录撑住 —— 实测卸载后留一个空壳，所以显式删掉。\n'
           'Type: filesandordirs; Name: "{app}\\vscode-extension"')
if OLD_DEL not in s:
    print('! 找不到 [UninstallDelete] 那一段')
    sys.exit(1)
s = s.replace(OLD_DEL, NEW_DEL, 1)

if crlf:
    s = s.replace('\n', '\r\n')
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('已修：UninstallRun 改用 call + 卸载时删 vscode-extension 目录')
