#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""合并设置页签 第 2 步：拆参数块 + 删掉被合并掉的三个页签渲染函数。

第 1 步（_merge_settings_tabs.py）已经做完：
  · 页签栏 8 个减到 5 个（界面 / 插件管理 / 模型来源 / 音效提醒 / 本地模型）
  · 老页签名 plugins/pluginparams/skills/approvals 一律落到插件管理
  · renderPluginsTab 一次取齐插件+参数+技能+团队+自动化+审批策略
  · 每个插件行下面挂它自己的参数块（pluginInlineHtml）

这一步：
  · pluginParamsHtml()（一次性返回六大块）→ pluginParamsFor(which)（按插件取对应那块）
  · 删掉 renderPluginParamsTab / renderSkillsTab / skillsTabHtml / renderApprovalsTab
    （页签没了；技能与审批策略的渲染搬进了 pluginInlineHtml）
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'web', 'index.html')

# 文件是 CRLF：统一成 LF 再做定点替换，最后写回 CRLF（否则锚点里的 \\n 一个都匹配不上）
s = io.open(P, encoding='utf-8', newline='').read()
CRLF = chr(13) + chr(10) in s
if CRLF:
    s = s.replace(chr(13) + chr(10), chr(10))
orig_len = len(s)

SECTIONS = [
    ('terminal', '// ---- 终端插件 ----'),
    ('loop', '// ---- Agent 大循环插件 ----'),
    ('subagent', '// ---- 子智能体插件 ----'),
    ('review', '// ---- 自动授权审查插件 ----'),
    ('team', '// ---- 智能体团队 ----'),
    ('automation', '// ---- 自动化任务 ----'),
]


def cut_function(text, start_marker):
    i = text.find(start_marker)
    if i < 0:
        raise SystemExit('找不到函数起点：%s' % start_marker)
    j = text.find('\n    },\n', i)
    if j < 0:
        raise SystemExit('找不到函数终点：%s' % start_marker)
    return i, j + len('\n    },\n')


# ---------------------------------------------- 1) 拆 pluginParamsHtml → pluginParamsFor
i, j = cut_function(s, '    pluginParamsHtml: function() {')
body = s[i:j]

idxs = []
for key, marker in SECTIONS:
    k = body.find(marker)
    if k < 0:
        raise SystemExit('参数块标记找不到：%s' % marker)
    idxs.append((key, k))
idxs.sort(key=lambda x: x[1])

head_end = body.find("        var h = '';")
if head_end < 0:
    raise SystemExit('pluginParamsHtml 结构变了：找不到 var h')
# 注意：body 是从函数签名那一行开始的，head 必须**去掉签名行**，
# 否则新函数里会残留一行 `pluginParamsHtml: function() {`（第一次就是这么错的）。
head = body[body.find('\n') + 1:head_end]
tail_start = body.rfind('        return h;')
if tail_start < 0:
    raise SystemExit('pluginParamsHtml 结构变了：找不到 return h')

blocks = {}
for n, (key, start) in enumerate(idxs):
    end = idxs[n + 1][1] if n + 1 < len(idxs) else tail_start
    blocks[key] = body[start:end].rstrip() + '\n'

out = ['    // 按插件取它自己那段参数（原来是"一页把六块全列出来"，现在贴在各自插件行下面）\n',
       '    pluginParamsFor: function(which) {\n',
       head,
       "        var parts = {};\n"]
for key, _ in SECTIONS:
    out.append("        if (which === '%s') {\n" % key)
    block = blocks[key].replace("h += ", "parts.%s = (parts.%s || '') + " % (key, key))
    for line in block.split('\n'):
        out.append(('    ' + line if line.strip() else line) + '\n')
    out.append('        }\n')
out.append("        return parts[which] || '';\n")
out.append('    },\n')

s = s[:i] + ''.join(out) + s[j:]

# ---------------------------------------------- 2) 删掉被合并掉的页签渲染函数
for marker in ('    renderPluginParamsTab: function() {',
               '    renderSkillsTab: function() {',
               '    skillsTabHtml: function() {',
               '    renderApprovalsTab: function() {'):
    a, b = cut_function(s, marker)
    s = s[:a] + s[b:]

if CRLF:
    s = s.replace(chr(10), chr(13) + chr(10))
# ---------------------------------------------- 3) 清掉已经作废的注释块
# 只认"起点/终点"两个锚点，中间那段注释原样跳过 —— 里面的引号是全角还是半角我一开始
# 猜错过一次（字符串比对不上），锚点法不吃这个亏。
STALE_B = """    // ====== 设置：插件参数 ======
    // （原来是独立页签，现在拆开贴在插件管理里各自的插件行下面：
    //   终端 / 大循环 / 子智能体 / 授权审查 / 团队 / 自动化 六块，见 pluginParamsFor）
"""
a = s.find('    // ====== 设置：技能 ======')
b = s.find('    // 按插件取它自己那段参数')
if a < 0 or b < 0 or b < a:
    raise SystemExit('作废注释块的锚点没找到（a=%d b=%d）——中止，不改文件' % (a, b))
s = s[:a] + STALE_B + s[b:]

io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('web/index.html：%d → %d 字符（第 2 步完成）' % (orig_len, len(s)))
sys.exit(0)
