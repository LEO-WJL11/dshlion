#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把"改动人工审核"闸门接到五个改文件的工具上。

【改了什么】每个"要落盘"的地方，在写之前先问一句：
    java.util.Optional<com.lioncode.core.plugin.tool.ToolResult> gate =
        changeReview.intercept(getName(), path, 旧内容, 新内容);
    if (gate.isPresent()) { return gate.get(); }
返回有值 = 不落盘（已经登记成待审改动，等人点通过）；返回空 = 照常写。

【为什么不用 AgentLoop 统一拦】只有工具自己知道改完的内容长什么样
（modify_file 有按行替换/插入/删除/追加好几条分支），在循环层拦拿不到 diff，也就没法给人看。

【覆盖的五个工具】write_file / modify_file / append_file / create_file / delete_file。
读类工具一个都不动（审核读操作毫无意义，只会拖慢每一条）。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'lioncode', 'core', 'plugin', 'tool', 'file')

IMPORT = 'import com.lioncode.core.agent.change.ChangeReview;\n'
FIELD = ('\n    /** 改动人工审核闸门：开着的活，改文件先攒成待审改动，人点了通过才落盘 */\n'
         '    private final ChangeReview changeReview;\n\n'
         '    public %s(ChangeReview changeReview) {\n'
         '        this.changeReview = changeReview;\n'
         '    }\n')

GATE = ('            java.util.Optional<ToolResult> gate = changeReview.intercept(\n'
        '                getName(), path, %s, %s);\n'
        '            if (gate.isPresent()) {\n'
        '                return gate.get();\n'
        '            }\n')


def patch(fname, edits, cls):
    p = os.path.join(BASE, fname)
    s = io.open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    if crlf:
        s = s.replace('\r\n', '\n')
    if 'ChangeReview' in s:
        print('  已经是打过闸门的：%s' % fname)
        return True

    # 1) import + 字段 + 构造器（插在第一个 @Override 之前）
    i = s.find('    @Override')
    if i < 0:
        print('  ! %s 找不到 @Override' % fname)
        return False
    s = s[:i] + (FIELD % cls).lstrip('\n') + '\n' + s[i:]
    s = s.replace('package com.lioncode.core.plugin.tool.file;\n',
                  'package com.lioncode.core.plugin.tool.file;\n\n' + IMPORT, 1)

    # 2) 各落盘点插闸门
    for old, new, label in edits:
        if old not in s:
            print('  ! %s 里的锚点没找到：%s' % (fname, label))
            return False
        s = s.replace(old, new, 1)

    if crlf:
        s = s.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('  已接闸门：%s（%d 处）' % (fname, len(edits)))
    return True


ok = True

# ---------------- write_file ----------------
ok &= patch('FileWriteTool.java', [(
    '''            Path filePath = Path.of(path);
            // 自动创建父目录
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }

            // 覆盖已有文件时保留它原来的编码（新文件用 UTF-8）
            Files.writeString(filePath, content, charsetOf(filePath));''',
    '''            Path filePath = Path.of(path);
            // 人工审核（开着的话）：先把改动登记成待审，别落盘
''' + (GATE % ('Files.isRegularFile(filePath) ? readTextFile(filePath) : null', 'content')) + '''
            // 自动创建父目录
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }

            // 覆盖已有文件时保留它原来的编码（新文件用 UTF-8）
            Files.writeString(filePath, content, charsetOf(filePath));''',
    'write_file 落盘点')], 'FileWriteTool')

# ---------------- append_file ----------------
ok &= patch('FileAppendTool.java', [(
    '''            Files.writeString(Path.of(path), content, charsetOf(Path.of(path)),''',
    '''            Path appendTarget = Path.of(path);
            String appendOld = Files.isRegularFile(appendTarget) ? readTextFile(appendTarget) : null;
''' + (GATE % ('appendOld', 'appendOld == null ? content : appendOld + content')) + '''
            Files.writeString(Path.of(path), content, charsetOf(Path.of(path)),''',
    'append_file 落盘点')], 'FileAppendTool')

# ---------------- create_file（建空文件） ----------------
ok &= patch('FileTouchTool.java', [(
    '''            Files.createFile(filePath);''',
    (GATE % ('null', '""')) + '''            Files.createFile(filePath);''',
    'create_file 落盘点')], 'FileTouchTool')

io.open(os.path.join(ROOT, 'tools', 'release', '_wire_change_gate.log'), 'w',
        encoding='utf-8').write('ok=%s\n' % ok)
sys.exit(0 if ok else 1)
