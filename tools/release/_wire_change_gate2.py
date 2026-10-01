#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 modify_file / delete_file 接上改动审核闸门（这两个的落盘点比前三个复杂一点）。

modify_file 有三类落盘点：
  ① 文件不存在、按 create 语义新建
  ② 文件已存在、create/write 当整体覆盖
  ③ 按 oldText 替换
  ④ 其余（按行替换/插入/删除/追加）最后统一那一处 Files.write
delete_file 有两类：删文件 / 删目录。

【为什么单独一个脚本】⑤→ 这几处的锚点跟前面三个不一样，混在一个脚本里出错不好定位；
分开跑，出问题一眼知道是哪个工具的哪一处。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'lioncode', 'core', 'plugin', 'tool', 'file')

IMPORT = 'import com.lioncode.core.agent.change.ChangeReview;\n'
GATE = ('            java.util.Optional<ToolResult> gate = changeReview.intercept(\n'
        '                getName(), path, %s, %s);\n'
        '            if (gate.isPresent()) {\n'
        '                return gate.get();\n'
        '            }\n')


def load(fname):
    p = os.path.join(BASE, fname)
    s = io.open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    return p, (s.replace('\r\n', '\n') if crlf else s), crlf


def save(p, s, crlf):
    if crlf:
        s = s.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


def add_field(s, cls):
    FIELD = ('    /** 改动人工审核闸门：开着的话，改文件先攒成待审改动，人点了通过才落盘 */\n'
             '    private final ChangeReview changeReview;\n\n'
             '    public %s(ChangeReview changeReview) {\n'
             '        this.changeReview = changeReview;\n'
             '    }\n\n' % cls)
    i = s.find('    @Override')
    s = s[:i] + FIELD + s[i:]
    s = s.replace('package com.lioncode.core.plugin.tool.file;\n',
                  'package com.lioncode.core.plugin.tool.file;\n\n' + IMPORT, 1)
    return s


ok = True

# ================= modify_file =================
p, s, crlf = load('FileModifyTool.java')
if 'ChangeReview' in s:
    print('  modify_file 已接过闸门')
else:
    s = add_field(s, 'FileModifyTool')

    edits = [
        # ① 文件不存在 → 按 create 新建
        ('''                    Files.writeString(filePath, getStringArg(arguments, "content", ""),
                        java.nio.charset.StandardCharsets.UTF_8);''',
         (GATE % ('null', 'getStringArg(arguments, "content", "")')) +
         '''                    Files.writeString(filePath, getStringArg(arguments, "content", ""),
                        java.nio.charset.StandardCharsets.UTF_8);''',
         '① 新建'),
        # ② 已存在 → create 当覆盖
        ('''                    Files.writeString(filePath, content, charsetOf(filePath));
                    return success("文件已存在，已按 create 覆盖写入: " + path);''',
         (GATE % ('String.join("\\n", lines)', 'content')) +
         '''                    Files.writeString(filePath, content, charsetOf(filePath));
                    return success("文件已存在，已按 create 覆盖写入: " + path);''',
         '② 覆盖'),
        # ③ 按 oldText 替换
        ('''                        writeTextPreservingCharset(filePath, replaced);''',
         (GATE % ('full', 'replaced')) +
         '''                        writeTextPreservingCharset(filePath, replaced);''',
         '③ 替换'),
        # ④ 其余分支最后统一那一处写
        ('''            // 按文件原本的编码写回：GBK 的仍是 GBK，不会被悄悄改成 UTF-8
            Files.write(filePath, lines, charsetOf(filePath));''',
         (GATE % ('String.join("\\n", lines)', 'String.join("\\n", lines)')) +
         '''            // 按文件原本的编码写回：GBK 的仍是 GBK，不会被悄悄改成 UTF-8
            Files.write(filePath, lines, charsetOf(filePath));''',
         '④ 按行改'),
    ]
    for old, new, label in edits:
        if old not in s:
            print('  ! modify_file 锚点没找到：%s' % label)
            ok = False
        else:
            s = s.replace(old, new, 1)
    if ok:
        # ③ 里 old 内容用 full（替换前的全文），④ 里 lines 已被就地改过 —— 需要保留原样：
        # 在读完 lines 之后立刻留一份原文，供 ④ 当"旧内容"
        s = s.replace('''            List<String> lines = readTextLines(filePath);''',
                      '''            List<String> lines = readTextLines(filePath);
            // 留一份原文：审核要拿它和"改完之后"比对（lines 后面会被就地改）
            String originalFull = String.join("\\n", lines);''', 1)
        s = s.replace('String.join("\\\\n", lines), String.join("\\\\n", lines)',
                      'originalFull, String.join("\\n", lines)')
        save(p, s, crlf)
        print('  modify_file 已接闸门（4 处）')

# ================= delete_file =================
p, s, crlf = load('FileDeleteTool.java')
if 'ChangeReview' in s:
    print('  delete_file 已接过闸门')
else:
    s = add_field(s, 'FileDeleteTool')
    # 删文件（两处 Files.delete(target) 之前）
    old_t = '''            Files.delete(target);'''
    if old_t in s:
        s = s.replace(old_t, (GATE % ('readTextFile(target) if Files.isRegularFile(target) else null', 'null')) + old_t, 1)
    else:
        print('  ! delete_file 找不到删文件那一处')
        ok = False
    save(p, s, crlf)
    print('  delete_file 已接闸门')

sys.exit(0 if ok else 1)
