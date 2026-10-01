#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把设置里散落的「插件 / 插件参数 / 技能 / 审批策略」四个页签合并成一个「插件管理」。

【为什么合并】用户的原话："如果设置里面有重复的设置，给它干掉，全部缩进插件管理。"
确实是四处在讲同一件事，还互相指路（插件参数页写着"开关在插件页签里"）：
  · 插件       —— 开关
  · 插件参数   —— 终端/大循环/子智能体/审查/团队/自动化的参数
  · 技能       —— 技能列表（技能本来就是一类插件，kind=SKILL）
  · 审批策略   —— 工具审批（和"自动授权审查插件"是同一件事的本地规则部分）
现在：插件管理一页里，每个插件的**开关和它自己的参数挨在一起**，技能进 SKILL 分组，
审批策略挪到授权审查插件下面。设置页签从 8 个减到 5 个。

【为什么不手改】web/index.html 有 3700 行，PowerShell 读写中文会变乱码（这一轮栽过两次），
所以用 Python 做定点替换，且每个锚点找不到就直接报错退出 —— 宁可失败也不要静默改坏。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'web', 'index.html')

# 文件是 CRLF：统一成 LF 再做定点替换，最后写回 CRLF（否则锚点里的 \\n 一个都匹配不上）
s = io.open(P, encoding='utf-8', newline='').read()
CRLF = chr(13) + chr(10) in s
if CRLF:
    s = s.replace(chr(13) + chr(10), chr(10))
orig_len = len(s)


def sub_once(pattern, repl, what, flags=0):
    """定点替换一次；没匹配到或匹配多次都算失败（避免改错地方）"""
    global s
    new, n = re.subn(pattern, repl, s, count=0, flags=flags)
    if n != 1:
        raise SystemExit('锚点 "%s" 命中 %d 次（应为 1 次）——中止，不改文件' % (what, n))
    s = new


# ---------------------------------------------------------------- 1) 页签栏
sub_once(
    r"            \+ '<button class=\"btn btn-sm\" id=\"setTabPlugins\".*?"
    r"\+ '<button class=\"btn btn-sm\" id=\"setTabLlama\"[^\n]*\n",
    "            + '<button class=\"btn btn-sm\" id=\"setTabPlugins\" onclick=\"App.settingsTab(\\'plugins\\')\">插件管理</button>'\n"
    "            + '<button class=\"btn btn-sm\" id=\"setTabProviders\" onclick=\"App.settingsTab(\\'providers\\')\">模型来源（本地 / API）</button>'\n"
    "            + '<button class=\"btn btn-sm\" id=\"setTabNotification\" onclick=\"App.settingsTab(\\'notification\\')\">音效提醒</button>'\n"
    "            + '<button class=\"btn btn-sm\" id=\"setTabLlama\" onclick=\"App.settingsTab(\\'llama\\')\">本地模型 / llama.cpp</button>'\n",
    '页签栏', re.S)

# ---------------------------------------------------------------- 2) 页签分发
sub_once(
    r"        var titles = \{'appearance':'setTabAppearance', 'plugins':'setTabPlugins',\n"
    r".*?        else if \(tab === 'llama'\) self\.renderLlamaTab\(\);\n",
    "        // 插件的开关、参数、技能、审批策略现在都在「插件管理」一页里 ——\n"
    "        // 老的页签名（plugins/pluginparams/skills/approvals）继续认，一律落到插件管理，\n"
    "        // 免得旧链接或记着老叫法的人点进来是空页。\n"
    "        var aliases = {'pluginparams':'plugins', 'skills':'plugins', 'approvals':'plugins'};\n"
    "        tab = aliases[tab] || tab;\n"
    "        var titles = {'appearance':'setTabAppearance', 'plugins':'setTabPlugins',\n"
    "                      'providers':'setTabProviders',\n"
    "                      'notification':'setTabNotification', 'llama':'setTabLlama'};\n"
    "        Object.keys(titles).forEach(function(t) {\n"
    "            var b = document.getElementById(titles[t]);\n"
    "            if (!b) return;\n"
    "            if (t === tab) {\n"
    "                b.style.background = 'var(--accent)';\n"
    "                b.style.color = 'var(--accent-fg)';\n"
    "                b.style.borderColor = 'var(--accent)';\n"
    "            } else {\n"
    "                b.style.background = '';\n"
    "                b.style.color = '';\n"
    "                b.style.borderColor = '';\n"
    "            }\n"
    "        });\n"
    "        this.currentSettingsTab = tab;\n"
    "        if (tab === 'appearance') self.renderAppearanceTab();\n"
    "        else if (tab === 'plugins') self.renderPluginsTab();\n"
    "        else if (tab === 'providers') self.renderProvidersTab();\n"
    "        else if (tab === 'notification') self.renderNotificationTab();\n"
    "        else if (tab === 'llama') self.renderLlamaTab();\n",
    '页签分发', re.S)

# ------------------------------------------------- 3) 插件管理页：一次把要用的数据都取回来
sub_once(
    r"    renderPluginsTab: function\(\) \{.*?\n    \},\n",
    """    // ====== 插件管理（唯一入口：开关 + 参数 + 技能 + 审批策略都在这） ======
    renderPluginsTab: function() {
        var self = this;
        var box = document.getElementById('settingsContent');
        if (!box) return;
        box.innerHTML = '<p class="set-note">正在加载插件…</p>';
        // 一次取齐：插件清单、各插件参数、技能、团队、自动化、审批策略。
        // 原来这些散在四个页签里各拉一次，现在一页拉完 —— 少四次来回，也不会出现
        // "插件页说开着、参数页说不生效"这种前后不一致。
        Promise.all([
            fetch('/api/plugins').then(function(r) { return r.json(); }),
            fetch('/api/plugins/settings').then(function(r) { return r.json(); }).catch(function() { return {}; }),
            fetch('/api/skills').then(function(r) { return r.json(); }).catch(function() { return {}; }),
            fetch('/api/plugins/team').then(function(r) { return r.json(); }).catch(function() { return {}; }),
            fetch('/api/plugins/automation').then(function(r) { return r.json(); }).catch(function() { return {}; }),
            fetch('/api/approvals').then(function(r) { return r.json(); }).catch(function() { return {}; })
        ]).then(function(res) {
            var pres = res[0];
            if (!self.respOk(pres)) { box.innerHTML = self.notReady('插件管理', 'GET /api/plugins', pres); return; }
            self.pluginsData = self.respList(pres, 'plugins') || [];
            self.pluginKinds = pres.kinds || ((pres.data && pres.data.kinds) || []);
            self.pluginDevMode = (pres.devMode === true) || !!(pres.data && pres.data.devMode === true);
            self.pluginsDir = pres.pluginsDir || ((pres.data && pres.data.pluginsDir) || '');

            var sd = res[1] && (res[1].data || res[1]);
            var auto = res[4] && (res[4].data || res[4]);
            self.pluginParams = {
                terminal: (sd && sd.terminal) || {},
                loop: (sd && sd.loop) || {},
                subagent: (sd && sd.subagent) || {},
                review: (sd && sd.review) || {},
                team: self.respList(res[3], 'team') || [],
                automation: (auto && auto.tasks) || []
            };
            self.skillsData = self.respList(res[2], 'skills') || [];
            self.approvalData = self.respList(res[5], 'approvals') || self.respList(res[5], 'tools') || [];
            box.innerHTML = self.pluginsTabHtml();
            self.loadActiveSkills();
        }).catch(function() {
            box.innerHTML = self.notReady('插件管理', 'GET /api/plugins');
        });
    },
""",
    'renderPluginsTab', re.S)

# ------------------------------------------- 4) 分组循环里插入"每个插件自己的东西"
sub_once(
    r"            items\.forEach\(function\(p\) \{ html \+= self\.pluginRow\(p\); \}\);\n",
    """            items.forEach(function(p) {
                html += self.pluginRow(p);
                // 插件的参数就贴在它自己那一条下面 —— 不用再去别的页签找
                var extra = self.pluginInlineHtml(p);
                if (extra) { html += extra; }
            });
""",
    '分组循环', re.S)

# ------------------------------------------- 5) 加：per-plugin 内联区块 + 审批策略加载
sub_once(
    r"    pluginRow: function\(p\) \{\n",
    """    // 某个插件自己的那一块（参数 / 成员 / 任务 / 技能清单 / 审批策略）。
    // 认 id 也认 kind：插件 id 是 plugin.terminal 这种，用户自己写的插件没有内联块，
    // 返回空字符串就行，不会在界面上留一个空壳。
    pluginInlineHtml: function(p) {
        var self = this;
        var id = String(p.id || '');
        var kind = String(p.kind || p.type || '').toUpperCase();
        var open = function(title, note, body) {
            return '<details class="plug-inline"><summary>' + self.escapeHtml(title) + '</summary>'
                 + (note ? '<div class="set-note">' + note + '</div>' : '')
                 + body + '</details>';
        };
        if (id === 'plugin.terminal') {
            return open('终端限制（每条命令跑多久 / 最多回多少）', '', self.pluginParamsFor('terminal'));
        }
        if (id === 'plugin.agent-loop') {
            return open('大循环参数（派发多少轮 / 单工具等多久 / 一轮几个）', '', self.pluginParamsFor('loop'));
        }
        if (id === 'plugin.subagent') {
            return open('子智能体参数（递归层级 / 数量 / 模型）', '', self.pluginParamsFor('subagent'));
        }
        if (id === 'plugin.approval-review') {
            return open('自动授权审查（审查模型 + 本地审批策略）',
                        '审查插件负责"叫另一个模型把关"；下面的审批策略是本地规则，两者一起生效。',
                        self.pluginParamsFor('review') + self.approvalsInlineHtml());
        }
        if (id === 'plugin.agent-team') {
            return open('智能体团队（每个成员用什么模式、干什么）', '', self.pluginParamsFor('team'));
        }
        if (id === 'plugin.automation') {
            return open('自动化任务（按时间或周期在会话里执行）', '', self.pluginParamsFor('automation'));
        }
        if (kind === 'SKILL') {
            return open('技能清单（含你自己放进 skills/ 的）', '', self.skillsInlineHtml());
        }
        return '';
    },

    // 技能原来是独立页签，现在挂在 SKILL 分组下面：一样能指定使用、一样能开关
    skillsInlineHtml: function() {
        var self = this;
        var skills = this.skillsData || [];
        var html = '<div class="set-note">当前会话指定使用：</div>'
                 + '<div id="skillPinned" style="margin:6px 0 4px">' + this.skillPinnedHtml() + '</div>'
                 + '<div class="set-msg" id="skillTabMsg"></div>';
        if (!skills.length) {
            html += '<div class="set-note" style="margin-top:6px">后端没返回任何技能。</div>';
        }
        skills.forEach(function(s) { html += self.skillRow(s); });
        return html;
    },

    // 审批策略原来是独立页签，现在挂在授权审查插件下面
    approvalsInlineHtml: function() {
        var tools = this.approvalData || [];
        var html = '<div class="set-note">默认全部自动批准；设为「需确认」的工具会被挡下并提示，'
                 + '「禁止」的工具模型看不到。这一层在本地判，不用等模型。</div>'
                 + '<div style="max-height:280px;overflow:auto;margin-top:6px">';
        if (!tools.length) {
            html += '<div class="set-note">后端没返回工具列表。</div>';
        }
        tools.forEach(function(t) {
            html += '<div style="display:flex;align-items:center;gap:10px;padding:6px 8px;margin-bottom:4px;'
                 +  'background:var(--panel-2);border:1px solid var(--border);border-radius:8px">'
                 +  '<div style="flex:1;min-width:0"><div style="font-size:12px;font-weight:600">'
                 +  self.escapeHtml(t.toolName) + '</div>'
                 +  '<div style="font-size:11px;color:var(--text-mute);overflow:hidden;text-overflow:ellipsis;'
                 +  'white-space:nowrap">' + self.escapeHtml(t.description || '') + '</div></div>'
                 +  '<select onchange="App.setToolPolicy(\\'' + t.toolId + '\\', this.value)" style="flex-shrink:0">'
                 +  '<option value="AUTO_APPROVE"' + (t.policy === 'AUTO_APPROVE' ? ' selected' : '') + '>自动批准</option>'
                 +  '<option value="CONFIRM"' + (t.policy === 'CONFIRM' ? ' selected' : '') + '>需确认</option>'
                 +  '<option value="BLOCK"' + (t.policy === 'BLOCK' ? ' selected' : '') + '>禁止</option>'
                 +  '</select></div>';
        });
        html += '</div>';
        return html;
    },

    pluginRow: function(p) {
""",
    'pluginInlineHtml', re.S)

if CRLF:
    s = s.replace(chr(10), chr(13) + chr(10))
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('web/index.html：%d → %d 字符（合并页签 1/2 步）' % (orig_len, len(s)))
