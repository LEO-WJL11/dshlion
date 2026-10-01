#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""WebUI 补两块新界面：待审改动（通过/打回）+ 上下文窗口指示。

【为什么这两块必须在 WebUI 上也做】"改动人工审核"出厂就是开的 ——
如果只有 VS Code 侧栏能点通过，那用 WebUI 的人会发现"AI 说写好了，文件却没变"，
而且是没有任何按钮的死路。所以 WebUI 必须有同样的入口。

【为什么用 Python 改】web/index.html 是 CRLF + 大量中文，PowerShell 会写乱码，
edit 工具的多行锚点在 CRLF 上匹配不上（刚踩过）。统一走 Python：读进来转 LF，
定点替换，写回 CRLF。
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


def sub_once(old, new, what):
    global s
    if new.strip().split('\n')[0] in s and what != 'html':
        print('  已有，跳过：%s' % what)
        return True
    n = s.count(old)
    if n != 1:
        print('  ! 锚点 "%s" 命中 %d 次（应为 1）' % (what, n))
        return False
    s = s.replace(old, new, 1)
    print('  已插入：%s' % what)
    return True


ok = True

# ---------------------------------------------------------------- 1) HTML
ok &= sub_once(
    '''                <div id="mentionPopup" class="mention-popup" style="display:none"></div>''',
    '''                <!-- 待人工审核的改动：AI 改的文件先攒在这里，点「通过」才真正落盘 -->
                <div id="changeBox" class="change-box" style="display:none"></div>
                <div id="mentionPopup" class="mention-popup" style="display:none"></div>''',
    '待审改动容器')

ok &= sub_once(
    '''                <span id="pluginCount">已加载 0 个插件</span>''',
    '''                <!-- 上下文窗口：默认 16K（省预填充）。点一下能手动改，AI 自己也能用工具改 -->
                <span id="ctxChip" title="上下文窗口：越大，每一轮要重算的前缀越多。AI 也能用 context_window 工具自己调"
                      style="cursor:pointer" onclick="App.promptContextWindow()">窗口 —</span>
                <span id="pluginCount">已加载 0 个插件</span>''',
    '上下文窗口指示')

# ---------------------------------------------------------------- 2) CSS
ok &= sub_once(
    '''        .set-group-title { font-size: 11.5px; color: var(--text-dim); margin: 12px 0 6px; letter-spacing: .5px; }''',
    '''        .set-group-title { font-size: 11.5px; color: var(--text-dim); margin: 12px 0 6px; letter-spacing: .5px; }
        /* 待审改动：贴在输入框上方，改文件等人工点头时才出现 */
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

# ---------------------------------------------------------------- 3) JS
CHANGE_JS = '''    // ====== 待人工审核的改动（AI 改文件 → 你点通过才落盘） ======
    /**
     * 拉一次待审列表并渲染。
     *
     * 【为什么要轮询】改动是模型在它自己那一轮里产生的，前端不知道什么时候冒出来；
     * 800ms 的事件轮询已经是现成的节拍，顺手带上这一个请求就够了（不另开定时器）。
     */
    refreshChanges: function() {
        var self = this;
        if (!this.sessionId) { return; }
        fetch('/api/changes?sessionId=' + encodeURIComponent(this.sessionId))
          .then(function(r) { return r.json(); })
          .then(function(res) {
            self.pendingChanges = (res && res.changes) || [];
            self.renderChanges();
          })
          .catch(function() { /* 拿不到就不显示，不影响别的 */ });
    },

    renderChanges: function() {
        var box = document.getElementById('changeBox');
        if (!box) return;
        var list = this.pendingChanges || [];
        if (!list.length) { box.style.display = 'none'; box.innerHTML = ''; return; }
        var self = this;
        var html = '<div class="chg-head">⏳ 有 ' + list.length
                 + ' 个改动等你审核（点「通过」才会真正写进文件）</div>';
        list.forEach(function(c) {
            html += '<div class="chg">'
                 +  '<div class="chg-path">' + self.escapeHtml(c.path) + '</div>'
                 +  '<div class="chg-meta">' + self.escapeHtml(c.toolName || '') + ' · '
                 +  self.escapeHtml(c.status === 'PENDING' ? '待审' : c.status)
                 +  (c.existed ? ' · 改已有文件' : ' · 新建文件') + '</div>'
                 +  '<pre>' + self.diffHtml(c.diff || '') + '</pre>'
                 +  '<div style="display:flex;gap:8px">'
                 +    '<button class="btn btn-sm btn-primary" onclick="App.approveChange(\\'' + self.escapeAttr(c.id) + '\\')">通过并写入</button>'
                 +    '<button class="btn btn-sm" onclick="App.rejectChange(\\'' + self.escapeAttr(c.id) + '\\')">打回并说明理由</button>'
                 +  '</div></div>';
        });
        box.innerHTML = html;
        box.style.display = '';
    },

    /** diff 上色：+ 行绿、- 行红，其余原样（转义过再拼） */
    diffHtml: function(diff) {
        var self = this;
        return diff.split('\\n').map(function(line) {
            var cls = line.indexOf('  +') === 0 ? 'add' : (line.indexOf('  -') === 0 ? 'del' : '');
            return cls ? '<span class="' + cls + '">' + self.escapeHtml(line) + '</span>'
                       : self.escapeHtml(line);
        }).join('\\n');
    },

    approveChange: function(id) {
        var self = this;
        var msg = document.getElementById('changeBox');
        fetch('/api/changes/' + encodeURIComponent(id) + '/approve', {method: 'POST'})
          .then(function(r) { return r.json(); })
          .then(function(res) {
            if (!res || res.success === false) { alert('通过失败：' + ((res && res.error) || '未知')); return; }
            self.refreshChanges();
          })
          .catch(function(e) { alert('通过失败：' + e); });
    },

    /** 打回：理由会回给模型，让它照着重写（相当于作业被打回） */
    rejectChange: function(id) {
        var self = this;
        var reason = prompt('打回理由（会回给 AI，让它照着重写）：', '这段不对，换个写法');
        if (reason === null) { return; }
        fetch('/api/changes/' + encodeURIComponent(id) + '/reject', {
            method: 'POST', headers: {'Content-Type':'application/json'},
            body: JSON.stringify({reason: reason})
        }).then(function(r) { return r.json(); })
          .then(function(res) {
            if (!res || res.success === false) { alert('打回失败：' + ((res && res.error) || '未知')); return; }
            self.refreshChanges();
          })
          .catch(function(e) { alert('打回失败：' + e); });
    },

    // ====== 上下文窗口（默认 16K，AI 也能自己调） ======
    refreshContextChip: function() {
        var self = this;
        fetch('/api/context' + (this.sessionId ? ('?sessionId=' + encodeURIComponent(this.sessionId)) : ''))
          .then(function(r) { return r.json(); })
          .then(function(res) {
            var c = (res && res.context) || {};
            self.contextInfo = c;
            var el = document.getElementById('ctxChip');
            if (!el) return;
            var lim = c.sessionLimit;
            el.textContent = '窗口 ' + (lim ? Math.round(lim / 1024) + 'K' : '不限')
                           + (c.overridden ? '（本会话已调）' : '');
          })
          .catch(function() {});
    },

    promptContextWindow: function() {
        var c = this.contextInfo || {};
        var cur = c.sessionLimit || c.defaultLimit || 16384;
        var v = prompt('上下文窗口（token）。默认 ' + (c.defaultLimit || 16384)
                     + '，越大每一轮预填充越贵。\\n填 0 = 不设限；填 16384 = 回到默认；留空不动。',
                     String(cur));
        if (v === null || v.trim() === '') { return; }
        var self = this;
        var body = {tokens: parseInt(v.trim(), 10)};
        if (this.sessionId) { body.sessionId = this.sessionId; }
        fetch('/api/context', {method: 'POST', headers: {'Content-Type':'application/json'},
                               body: JSON.stringify(body)})
          .then(function(r) { return r.json(); })
          .then(function(res) {
            if (!res || res.success === false) { alert('设置失败：' + ((res && res.error) || '未知')); return; }
            self.refreshContextChip();
          })
          .catch(function(e) { alert('设置失败：' + e); });
    },

'''

ok &= sub_once(
    '''    // ====== 插件管理（唯一入口：开关 + 参数 + 技能 + 审批策略都在这） ======''',
    CHANGE_JS + '''    // ====== 插件管理（唯一入口：开关 + 参数 + 技能 + 审批策略都在这） ======''',
    '待审改动/窗口的 JS')

# 把两个刷新挂进现成的 800ms 轮询，不另开定时器
ok &= sub_once(
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
