#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""合并设置页签 第 3 步：把"重画"接到新结构上。

1) 加/删团队成员、加/删自动化任务之后原来的做法是 renderPluginParamsTab()（整页重建）。
   现在没有那一页了，改成 rerenderPlugins()（会记住哪些折叠块是打开的，画完再展开回去）。
2) 技能那块的容器加个 id（skillsInlineBox），这样指定/取消技能只刷技能列表，不用整页重建。
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

before = s

# 1) 重画入口
n1 = s.count('self.renderPluginParamsTab();')
s = s.replace('self.renderPluginParamsTab();', 'self.rerenderPlugins();')

# 2) 技能容器
OLD = """        var html = '<div class="set-note">当前会话指定使用：</div>'
                 + '<div id="skillPinned" style="margin:6px 0 4px">' + this.skillPinnedHtml() + '</div>'
                 + '<div class="set-msg" id="skillTabMsg"></div>';
        if (!skills.length) {
            html += '<div class="set-note" style="margin-top:6px">后端没返回任何技能。</div>';
        }
        skills.forEach(function(s) { html += self.skillRow(s); });
        return html;
"""
NEW = """        return '<div class="set-note">当前会话指定使用：</div>'
             + '<div id="skillPinned" style="margin:6px 0 4px">' + this.skillPinnedHtml() + '</div>'
             + '<div class="set-msg" id="skillTabMsg"></div>'
             + '<div id="skillsInlineBox">' + this.skillsRowListHtml() + '</div>';
"""
if OLD in s:
    s = s.replace(OLD, NEW)
    n2 = 1
else:
    n2 = 0

if n1 == 0 or n2 == 0:
    print('renderPluginParamsTab 引用改了 %d 处；技能容器改了 %d 处' % (n1, n2))
    raise SystemExit('有锚点没命中，文件未改动')

if CRLF:
    s = s.replace('\n', '\r\n')
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('第 3 步完成：重画入口 %d 处、技能容器 1 处；文件 %d → %d 字符' % (n1, len(before), len(s)))
sys.exit(0)
