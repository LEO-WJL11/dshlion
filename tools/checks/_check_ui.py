# -*- coding: utf-8 -*-
r"""界面回归：两套配色（CSS 变量 + 设置里切换）、设置面板（插件 / 技能 / 界面）、输入框 @ 补全。

为什么要真跑一遍 JS（假 DOM + node），而不只是 grep 关键字：
  · 主题切换、@ 补全的候选/键盘/插入、面板渲染与失败回滚，都是"写了但一跑就报错"的重灾区；
  · 这些函数抛异常的表现就是整页白屏或输入框卡住，静态 grep 一个字都看不出来。

三类断言：
  1) 静态：文件里该有的东西在不在、硬编码颜色有没有扫干净（变量定义行不算）；
  2) 行为：把 <script> 抽出来在假 DOM 里真跑（主题 / @ 补全 / 插件面板 / 技能面板 / 降级）；
  3) 网络：本地起个静态服务，真 GET 一次 / 确认 HTTP 200、字节数与文件一致（没被写坏）。

跑法: python _check_ui.py
"""
import io
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UI = os.path.join(ROOT, 'web', 'index.html')
WEB = os.path.join(ROOT, 'web')

ok_all = True
n_ok = 0


def check(label, ok, detail=''):
    global ok_all, n_ok
    ok_all = ok_all and bool(ok)
    if ok:
        n_ok += 1
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


html = io.open(UI, encoding='utf-8', newline='').read()
app_js = re.search(r'(?s)<script>(.*?)</script>', html).group(1)

# =====================================================================
# 1) 静态检查
# =====================================================================
print('=' * 72)
print('1) 静态检查：主题变量 / 面板 DOM / 接口调用 / 老逻辑没被破坏')
print('=' * 72)

# ---- 1.1 两套主题的变量定义 ----
VARS = ['--bg', '--panel', '--border', '--text', '--text-dim', '--accent']
dark_block = re.search(r'(?s)html\[data-theme="dark"\][^{]*\{(.*?)\n\s*\}', html)
light_block = re.search(r'(?s)html\[data-theme="light"\][^{]*\{(.*?)\n\s*\}', html)
check('★ 暗色主题的变量块在（html[data-theme="dark"]）', bool(dark_block))
check('★ 亮色主题的变量块在（html[data-theme="light"]）', bool(light_block))
missing = []
for blk, who in ((dark_block, '暗色'), (light_block, '亮色')):
    body = blk.group(1) if blk else ''
    for v in VARS:
        if not re.search(r'(?m)^\s*' + re.escape(v) + r'\s*:', body):
            missing.append(who + ' 缺 ' + v)
check('★ 两套主题都定义了 ' + ' / '.join(VARS), not missing, ' | '.join(missing))
check('<html> 上挂了 data-theme（默认暗色）', 'data-theme="dark"' in html.split('<style>')[0])

# ---- 1.2 硬编码颜色扫干净（变量定义行本身除外）----
VAR_DEF = re.compile(r'^\s*--[A-Za-z0-9-]+\s*:')
COLOR_PAT = re.compile(r'#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(|'
                       r'\b(?:white|black|red|green|blue|gray|grey|orange|yellow|purple|cyan|magenta)\b\s*[;})]')
leftover = []
for i, ln in enumerate(html.replace('\r\n', '\n').split('\n'), 1):
    if VAR_DEF.match(ln):
        continue                       # 变量定义行：颜色值本来就该写在这儿
    for m in COLOR_PAT.finditer(ln):
        leftover.append('%d:%s' % (i, m.group(0).strip()))
check('★ 变量定义之外没有残留的硬编码颜色', not leftover,
      '还有 %d 处：%s' % (len(leftover), ', '.join(leftover[:8])))
OLD = ['#6c5ce7', '#1a1a2e', '#16213e', '#1e1e3a', '#a0a0b8', '#e8e8f0',
       '#6a6a80', '#2d2d4a', '#00b894', '#fdcb6e', '#74b9ff', '#a29bfe', '#e17055']
still = [c for c in OLD if c in html]
check('★ 旧配色一个不剩（紫/深蓝/霓虹色全换掉）', not still, '还剩：' + ','.join(still))

# ---- 1.3 设置面板 / 浮层的 DOM ----
check('设置面板的内容容器在（settingsContent）', 'id="settingsContent"' in html)
check('★ 设置里有「界面」页签（主题切换入口）',
      'id="setTabAppearance"' in html and "settingsTab(\\'appearance\\')" in html)
# ---- 设置页签合并：插件 / 插件参数 / 技能 / 审批策略 → 一个「插件管理」 ----
# 用户的原话："如果设置里面有重复的设置，给它干掉，全部缩进插件管理。"
# 所以这里反过来断言：**那三个旧页签必须不存在**，而且老页签名要还能落到插件管理
# （旧链接、老记法点进来不能是空页）。
check('★ 设置里有「插件管理」页签（插件唯一入口）',
      'id="setTabPlugins"' in html and "settingsTab(\\'plugins\\')" in html
      and '插件管理' in html)
check('★ 被合并掉的三个页签不再存在（插件参数 / 技能 / 审批策略）',
      all(x not in html for x in ['setTabPluginParams', 'setTabSkills', 'setTabApprovals']),
      '旧页签按钮还有：' + ','.join([x for x in ['setTabPluginParams', 'setTabSkills', 'setTabApprovals'] if x in html]))
check('★ 老页签名一律落到插件管理（不会点出空页）',
      "'pluginparams':'plugins'" in app_js and "'skills':'plugins'" in app_js
      and "'approvals':'plugins'" in app_js)
check('★ 每个插件行下面挂自己的参数块（pluginParamsFor + pluginInlineHtml）',
      'pluginParamsFor: function' in app_js and 'pluginInlineHtml: function' in app_js)
check('★ 六个参数块按插件分发（终端/大循环/子智能体/审查/团队/自动化）',
      all(("which === '%s'" % k) in app_js for k in
          ['terminal', 'loop', 'subagent', 'review', 'team', 'automation']))
check('★ 技能与审批策略的渲染进了插件页（不再有独立页签函数）',
      'skillsInlineHtml: function' in app_js and 'approvalsInlineHtml: function' in app_js
      and 'renderSkillsTab: function' not in app_js
      and 'renderApprovalsTab: function' not in app_js
      and 'renderPluginParamsTab: function' not in app_js)
check('★ 重画插件页时保留展开状态（加成员/加任务不会把折叠块合上）',
      'rerenderPlugins: function' in app_js and 'plug-inline' in app_js)
check('原来的设置页签都还在（模型来源 / 音效 / llama）',
      all(x in html for x in ['setTabProviders', 'setTabNotification', 'setTabLlama']))
check('★ @ 补全的候选浮层元素在（mentionPopup）', 'id="mentionPopup"' in html and 'mention-popup' in html)
check('★ 引用小标签的容器在（refBar）', 'id="refBar"' in html and 'ref-chip' in html)

# ---- 1.4 前端确实调了这些接口 ----
def has(pattern):
    return re.search(pattern, app_js) is not None


check('★ JS 引用了 /api/plugins', has(r"fetch\('/api/plugins'\)"))
check('★ JS 引用了 /api/plugins/{id}/enable|disable', has(r"'/api/plugins/'\s*\+\s*encodeURIComponent\(id\)")
      and has(r"\? 'enable' : 'disable'"))
check('★ JS 引用了 /api/plugins/reload', "'/api/plugins/reload'" in app_js)
check('★ JS 引用了 /api/plugins/dev-mode', "'/api/plugins/dev-mode'" in app_js)
check('★ JS 引用了 /api/plugins/scaffold', "'/api/plugins/scaffold'" in app_js)
check('★ JS 引用了 /api/skills（列表 / 开关 / 重载）',
      "'/api/skills'" in app_js and "'/api/skills/reload'" in app_js
      and has(r"'/api/skills/'\s*\+\s*encodeURIComponent\(id\)"))
check('★ JS 引用了 /api/skills/active（当前会话指定技能）',
      "'/api/skills/active?sessionId='" in app_js and "'/api/skills/active'" in app_js)
check('★ JS 引用了 /api/context/mentions，且带 kind/root/sessionId/limit',
      "'/api/context/mentions?q='" in app_js and '&kind=all&limit=20' in app_js
      and "'&root='" in app_js and "'&sessionId='" in app_js)
check('@ 补全做了 200ms 防抖', re.search(r'setTimeout\(function\(\)\s*\{\s*self\.fetchMentions', app_js)
      is not None and '}, 200);' in app_js)
check('@ 补全请求带序号（防乱序覆盖）',
      '_mentionSeq' in app_js and 'seq !== self._mentionSeq' in app_js)

# ---- 1.5 不能破坏的老行为 ----
check('★ 事件去重逻辑还在（seenEvents / seenEventOrder）',
      'seenEvents: {}' in app_js and 'seenEventOrder' in app_js
      and 'if (self.seenEvents[eid]) return;' in app_js)
poll = re.search(r'(?s)pollEvents: function\(\)\s*\{(.*?)\n    \},', app_js)
poll_body = poll.group(1) if poll else ''
check('★ 工具行渲染还在 pollEvents 里（一行一条）',
      '🔧 调用工具：' in poll_body and '✅ ' in poll_body and '❌ ' in poll_body
      and ' 完成</div>' in poll_body and ' 失败</div>' in poll_body)
check('★ pollEvents 里每个事件只画一行（3 个分支各 push 一次）',
      poll_body.count('self.toolLines.push(') == 3,
      '实际 %d 次 push' % poll_body.count('self.toolLines.push('))
hist = re.search(r'(?s)loadHistory: function\(sid\)\s*\{(.*?)\n    \},', app_js)
hist_body = hist.group(1) if hist else ''
check('★ 历史里的工具行格式也在（🔧/✅/❌）',
      '🔧 调用工具：' in hist_body and '❌ ' in hist_body and '✅ ' in hist_body
      and "' 完成'" in hist_body and "' 失败'" in hist_body)
check('轮询 / 流式 / 会话切换 / 发送都还在',
      all(k in app_js for k in ['pollEvents:', 'loadHistory:', 'switchSession:', 'send: function()',
                                'seenEvents = {};', 'formatContent:']))
check('新增面板每个 fetch 都有 catch（坏一个面板不拖垮整页）',
      app_js.count('.catch(function() {') + app_js.count('.catch(function(e)') >= 8
      and 'respOk: function(res)' in app_js and 'notReady: function(what, api, res)' in app_js)

# ---- 1.6 JS 语法：node --check ----
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionui_check')
os.makedirs(TMP, exist_ok=True)
js_path = os.path.join(TMP, 'app_check.js')
io.open(js_path, 'w', encoding='utf-8', newline='\n').write(app_js)
node = None
for cand in ('node', 'node.exe'):
    try:
        subprocess.run([cand, '--version'], capture_output=True, timeout=30)
        node = cand
        break
    except Exception:
        continue
if node:
    r = subprocess.run([node, '--check', js_path], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=120)
    check('★ 抽出来的 <script> 过了 node --check（语法错=整页死）', r.returncode == 0,
          (r.stderr or '').strip()[:300])
else:
    r = subprocess.run([sys.executable, js_path], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=120)
    check('★ 没有 node，改成直接执行做语法检查', 'SyntaxError' not in (r.stderr or ''),
          (r.stderr or '').strip()[:200])

# =====================================================================
# 2) 行为：假 DOM + node 真跑一遍
# =====================================================================
print()
print('=' * 72)
print('2) 行为：主题切换 / @ 补全 / 插件面板 / 技能面板 / 后端没就绪时的降级')
print('=' * 72)

PREAMBLE = r'''
// ---------------- 最小假 DOM ----------------
const __store = {};
globalThis.localStorage = {
  getItem: k => (k in __store ? __store[k] : null),
  setItem: (k, v) => { __store[k] = String(v); },
  removeItem: k => { delete __store[k]; }
};
const __attrs = {};
const __els = {};
function __mkEl(id) {
  return {
    id: id, innerHTML: '', textContent: '', value: '', checked: false, disabled: false,
    style: {}, dataset: {}, selectionStart: 0,
    classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
    appendChild(){}, removeChild(){}, setAttribute(k, v){ __attrs[k] = v; }, removeAttribute(){},
    addEventListener(){}, removeEventListener(){}, focus(){}, blur(){}, click(){},
    setSelectionRange(a, b){ this.selectionStart = a; this.selectionEnd = b; },
    querySelector(){ return null; }, querySelectorAll(){ return []; },
    getBoundingClientRect(){ return {top:0,left:0,width:0,height:0}; },
    scrollIntoView(){}, insertAdjacentHTML(){}, remove(){}
  };
}
globalThis.document = {
  getElementById(id) { return __els[id] || (__els[id] = __mkEl(id)); },
  querySelector() { return null; },
  querySelectorAll() { return []; },
  createElement(tag) { return __mkEl('new-' + tag); },
  addEventListener(){}, removeEventListener(){},
  body: __mkEl('body'), documentElement: __mkEl('html'),
  execCommand(){ return true; }
};
globalThis.window = globalThis;
globalThis.window.addEventListener = function(){};
globalThis.window.removeEventListener = function(){};
globalThis.location = { href: 'http://127.0.0.1:8899/', reload(){} };
globalThis.navigator = { clipboard: { writeText: () => Promise.resolve() }, userAgent: 'node' };
globalThis.alert = function(m) { (globalThis.__alerts = globalThis.__alerts || []).push(String(m)); };
globalThis.confirm = function() { return true; };
globalThis.prompt = function() { return 'x'; };
globalThis.EventSource = function() { this.close = function(){}; this.addEventListener = function(){}; };
globalThis.marked = { parse: s => s };
globalThis.hljs = { highlightElement(){}, highlight(){ return { value: '' }; } };
globalThis.AbortController = function() { this.signal = {}; this.abort = function(){}; };

// 定时器：记下来手动触发（防抖那 200ms 不能真等）
globalThis.__timers = [];
globalThis.setTimeout = function(fn, ms) { globalThis.__timers.push({fn: fn, ms: ms}); return globalThis.__timers.length; };
globalThis.clearTimeout = function(id) { if (id) { globalThis.__timers[id - 1] = null; } };
globalThis.setInterval = function() { return 0; };
globalThis.clearInterval = function(){};
globalThis.__runTimers = function() {
  const list = globalThis.__timers.slice();
  globalThis.__timers = [];
  list.forEach(t => { if (t) { t.fn(); } });
};

// matchMedia：测试里可以随时改系统配色
globalThis.__mediaLight = false;
globalThis.matchMedia = function(q) {
  return { media: q, matches: globalThis.__mediaLight, addEventListener(){}, removeEventListener(){}, addListener(){} };
};

// fetch：默认把应用启动时那些请求糊弄过去，测试里再按需换 __fetchHandler
globalThis.__fetchLog = [];
globalThis.__fetchHandler = function(url, opt) {
  let data = { success: true, data: [] };
  if (url.indexOf('/api/chat/config') >= 0) { data = { success: true, data: {} }; }
  else if (url.indexOf('/api/native-dialog/common-dirs') >= 0) { data = { success: true, data: { '用户目录': 'C:\\u' } }; }
  else if (url.indexOf('/api/workspaces') >= 0 && opt && opt.method === 'POST') { data = { success: true, data: { id: 'C:\\u', path: 'C:\\u' } }; }
  else if (url.indexOf('/api/sessions') >= 0 && opt && opt.method === 'POST') { data = { success: true, data: { sessionId: 's1' } }; }
  else if (url.indexOf('/api/runtime/mode') >= 0) { data = { success: true, data: { mode: 'local' } }; }
  else if (url.indexOf('/api/plugins') >= 0) { data = { success: true, data: [] }; }
  return { ok: true, status: 200, json: () => Promise.resolve(data) };
};
globalThis.fetch = function(url, opt) {
  const u = String(url);
  globalThis.__fetchLog.push({ url: u, method: (opt && opt.method) || 'GET', body: (opt && opt.body) || '' });
  try {
    return Promise.resolve(globalThis.__fetchHandler(u, opt || {}));
  } catch (e) {
    return Promise.reject(e);
  }
};

let __fails = [];
function ok(cond, label, extra) {
  if (cond) { console.log('  [OK]   ' + label); }
  else { console.log('  [FAIL] ' + label + (extra ? '  ' + extra : '')); __fails.push(label); }
}
function tick() { return new Promise(function(r) { setTimeout0(r); }); }
function setTimeout0(fn) { fn(); }
function info(m) { console.log('    ' + m); }
'''

TESTS = r'''
(async function() {
 try {
  // ==================== 1. 主题 ====================
  console.log('--- 主题：切换 / 持久化 / 跟随系统 ---');
  __store['lionbox.theme'] = 'light';
  App.initTheme();
  ok(__attrs['data-theme'] === 'light', '★ 上次选了亮色 → 启动就挂 data-theme="light"', String(__attrs['data-theme']));

  __store['lionbox.theme'] = 'dark';
  App.initTheme();
  ok(__attrs['data-theme'] === 'dark', '★ 上次选了暗色 → 启动就挂 data-theme="dark"', String(__attrs['data-theme']));

  __mediaLight = true;
  delete __store['lionbox.theme'];
  App.initTheme();
  ok(__attrs['data-theme'] === 'light', '★ 没选过 + 系统是浅色 → 默认亮色（跟随系统）', String(__attrs['data-theme']));
  __mediaLight = false;
  App.initTheme();
  ok(__attrs['data-theme'] === 'dark', '★ 没选过 + 系统是深色 → 默认暗色', String(__attrs['data-theme']));

  App.setTheme('light');
  ok(__attrs['data-theme'] === 'light' && __store['lionbox.theme'] === 'light',
     '★ 点「亮色」：立即生效 + 记进 localStorage（刷新后保持）');
  App.setTheme('dark');
  ok(__attrs['data-theme'] === 'dark' && __store['lionbox.theme'] === 'dark', '★ 点「暗色」同样立即生效');
  App.setTheme('auto');
  ok(__store['lionbox.theme'] === 'auto' && __attrs['data-theme'] === 'dark',
     '★ 点「跟随系统」：按系统配色算（当前系统深色 → 暗色）');

  App.currentSettingsTab = 'appearance';
  App.renderAppearanceTab();
  var apHtml = document.getElementById('settingsContent').innerHTML;
  ok(apHtml.indexOf('theme-card') >= 0 && apHtml.indexOf('暗色') >= 0 && apHtml.indexOf('亮色') >= 0,
     '设置 → 界面：三个主题卡都在（暗色 / 亮色 / 跟随系统）');
  ok(apHtml.indexOf('var(--sw-dark)') >= 0 && apHtml.indexOf('#0d0e10') < 0,
     '主题卡的色块也用变量，没写死颜色');

  // ==================== 2. @ 补全 ====================
  console.log('--- @ 补全：候选 / 键盘 / 插入 / 引用标签 ---');
  var input = document.getElementById('msgInput');
  var pop = document.getElementById('mentionPopup');
  var refBar = document.getElementById('refBar');

  globalThis.__defaultHandler = globalThis.__fetchHandler;
  globalThis.__fetchHandler = function(url, opt) {
    if (url.indexOf('/api/context/mentions') < 0) { return globalThis.__defaultHandler(url, opt); }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, items: [
      { type: 'file', id: 'f1', label: 'src/main/App.java', detail: '工作区', insert: '@file:src/main/App.java' },
      { type: 'history', id: 'h1', label: '上次聊的会话', detail: '3 天前', insert: '@history:sess-9' },
      { type: 'skill', id: 'pdf', label: 'pdf 技能', detail: '技能', insert: '@skill:pdf' }
    ] }) };
  };
  App.ws = 'C:\\ws';
  App.sessionId = 'sess-1';
  App.hideMentionPopup();

  input.value = '帮我看看 @sr';
  input.selectionStart = input.value.length;
  globalThis.__fetchLog = [];
  App.onInputChanged();
  ok(globalThis.__timers.length === 1 && globalThis.__timers[0].ms === 200,
     '★ 输入 @ 后不立刻请求，先等 200ms（防抖）',
     '定时器：' + JSON.stringify(globalThis.__timers.map(function(t) { return t.ms; })));
  ok(pop.style.display !== 'block', '防抖期间浮层还没弹（不闪）');
  globalThis.__runTimers();
  for (var i = 0; i < 4; i++) { await tick(); }
  var url = globalThis.__fetchLog.filter(function(u) { return u.url.indexOf('/api/context/mentions') >= 0; })[0];
  ok(!!url, '防抖到点后请求了 /api/context/mentions');
  ok(!!url && url.url.indexOf('q=sr') >= 0 && url.url.indexOf('kind=all') >= 0
       && url.url.indexOf('root=') >= 0 && url.url.indexOf('sessionId=sess-1') >= 0,
     '★ 请求带上了 q / kind=all / root / sessionId', url ? url.url : '');
  ok(pop.style.display === 'block' && pop.innerHTML.indexOf('src/main/App.java') >= 0,
     '★ 候选浮层弹出来了，里面有文件名');
  ok(pop.innerHTML.indexOf('mention-item active') >= 0, '第一项默认高亮');
  ok(pop.innerHTML.indexOf('文件') >= 0 && pop.innerHTML.indexOf('历史') >= 0
       && pop.innerHTML.indexOf('技能') >= 0, '候选项标了类型（文件 / 历史 / 技能）');

  // 键盘：↓ 换一项、Esc 关掉
  App.mentionKeydown({ key: 'ArrowDown', preventDefault: function(){} });
  ok(pop.innerHTML.indexOf('mention-item active') > 0
     && pop.innerHTML.split('mention-item active').length === 2
     && pop.innerHTML.indexOf('active" onmousedown="event.preventDefault();App.mentionPick(1)') > 0,
     '★ ↓ 把高亮挪到第二项');
  App.mentionKeydown({ key: 'ArrowUp', preventDefault: function(){} });
  ok(pop.innerHTML.indexOf('App.mentionPick(0)') > 0
     && pop.innerHTML.indexOf('mention-item active" onmousedown="event.preventDefault();App.mentionPick(0)') > 0,
     '★ ↑ 挪回第一项');
  App.mentionKeydown({ key: 'Escape', preventDefault: function(){} });
  ok(pop.style.display === 'none', '★ Esc 关掉浮层');
  ok(App.mentionKeydown({ key: 'Enter', preventDefault: function(){} }) === false,
     '浮层关着时 Enter 不拦截（回车照样能发消息）');

  // Enter / Tab 确认：插入 insert 文本
  input.value = '看看 @hi';
  input.selectionStart = input.value.length;
  App.onInputChanged();
  globalThis.__runTimers();
  for (var j = 0; j < 4; j++) { await tick(); }
  var consumed = App.mentionKeydown({ key: 'Enter', preventDefault: function(){} });
  ok(consumed === true, '★ 浮层开着时 Enter 先给浮层用（不会误发消息）');
  ok(input.value === '看看 @file:src/main/App.java ', '★ 选中后把 @ 词换成 insert 文本', JSON.stringify(input.value));
  ok(pop.style.display === 'none', '选完浮层收起');
  ok(refBar.style.display === 'flex' && refBar.innerHTML.indexOf('src/main/App.java') >= 0,
     '★ 输入框上方出现引用小标签');
  ok(refBar.innerHTML.indexOf('App.removeRef(0)') >= 0, '引用标签上带 × 删除按钮');

  // Tab 也能确认（先把高亮挪到第三项：技能）
  input.value = '再看 @sk';
  input.selectionStart = input.value.length;
  App.onInputChanged();
  globalThis.__runTimers();
  for (var k = 0; k < 8; k++) { await tick(); }
  App.mentionKeydown({ key: 'ArrowDown', preventDefault: function(){} });
  App.mentionKeydown({ key: 'ArrowDown', preventDefault: function(){} });
  App.mentionKeydown({ key: 'Tab', preventDefault: function(){} });
  ok(input.value === '再看 @skill:pdf ', '★ Tab 也能确认候选项', JSON.stringify(input.value));
  App.removeRef(0);
  ok(input.value.indexOf('@skill:pdf') < 0, '★ 点 × 把引用从输入框里删掉', JSON.stringify(input.value));
  ok(refBar.style.display === 'none' || refBar.innerHTML.indexOf('@skill:pdf') < 0,
     '删掉后标签也跟着消失');

  // 鼠标点选（onmousedown 里就是 mentionPick）
  input.value = '@sk';
  input.selectionStart = 3;
  App._mentionItems = [{ type: 'skill', label: 'pdf 技能', insert: '@skill:pdf' }];
  App._mentionIndex = 0;
  App.mentionPick(0);
  ok(input.value === '@skill:pdf ', '鼠标点候选项也是同一套插入逻辑', JSON.stringify(input.value));

  // 后端没就绪：静默，不报错、不弹窗、不卡输入
  console.log('--- @ 补全：后端没就绪时的降级 ---');
  App.hideMentionPopup();
  input.value = '@zzz';
  input.selectionStart = 4;
  globalThis.__fetchHandler = function() { return Promise.reject(new Error('404')); };
  var threw = null;
  try { App.onInputChanged(); globalThis.__runTimers(); for (var m = 0; m < 4; m++) { await tick(); } }
  catch (e) { threw = e; }
  ok(threw === null, '★ 端点不存在时 onInputChanged 不抛异常（输入框不会卡住）', threw ? String(threw) : '');
  ok(pop.style.display === 'none', '★ 请求失败时浮层静默不出现');
  globalThis.__fetchHandler = function() {
    return { ok: false, status: 404, json: () => Promise.resolve({ timestamp: 1, status: 404, error: 'Not Found' }) };
  };
  try { App.onInputChanged(); globalThis.__runTimers(); for (var n = 0; n < 4; n++) { await tick(); } }
  catch (e) { threw = e; }
  ok(threw === null && pop.style.display === 'none', 'HTTP 404 的 JSON 也不会让浮层弹出来或报错');

  // 乱序响应：慢的旧响应回来不许盖掉新结果
  console.log('--- @ 补全：乱序响应 ---');
  var pending = [];
  var __deferBase = globalThis.__fetchHandler;
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/api/context/mentions') < 0) { return __deferBase(u, opt); }
    return new Promise(function(resolve) {
      pending.push({ url: u, done: function(obj) {
        resolve({ ok: true, status: 200, json: function() { return Promise.resolve(obj); } });
      }});
    });
  };
  input.value = '@a'; input.selectionStart = 2;
  App.onInputChanged(); globalThis.__runTimers();
  input.value = '@ab'; input.selectionStart = 3;
  App.onInputChanged(); globalThis.__runTimers();
  for (var p = 0; p < 3; p++) { await tick(); }
  ok(pending.length === 2, '两次输入发出两个请求', '实际 ' + pending.length);
  pending[1].done({ ok: true, items: [{ type: 'file', label: '新结果', insert: '@file:new' }] });
  for (var q = 0; q < 3; q++) { await tick(); }
  pending[0].done({ ok: true, items: [{ type: 'file', label: '旧结果', insert: '@file:old' }] });
  for (var r2 = 0; r2 < 3; r2++) { await tick(); }
  ok(pop.innerHTML.indexOf('新结果') >= 0 && pop.innerHTML.indexOf('旧结果') < 0,
     '★ 慢的旧响应被丢掉，不会盖掉新结果', pop.innerHTML.slice(0, 80));

  // ==================== 3. 插件面板 ====================
  console.log('--- 设置 → 插件 ---');
  App.hideMentionPopup();
  App.currentSettingsTab = 'plugins';
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/api/plugins') === 0 && (!opt || opt.method !== 'POST')) {
      return { ok: true, status: 200, json: () => Promise.resolve({
        ok: true, devMode: false, pluginsDir: 'C:\\plugins',
        kinds: [{ code: 'TOOL', label: '工具' }, { code: 'SKILL', label: '技能' }],
        plugins: [
          { id: 'web_search', name: 'web_search', kind: 'TOOL', description: '联网搜索',
            version: '1.0', enabled: true, builtin: true, source: 'BUILTIN', error: null },
          { id: 'pdf', name: 'pdf', kind: 'SKILL', description: '读 PDF', version: '0.9',
            enabled: false, builtin: false, source: 'EXTERNAL', error: '配置文件缺失' }
        ] }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
  };
  App.renderPluginsTab();
  // 插件管理一页要拉 6 个接口（插件 / 参数 / 技能 / 团队 / 自动化 / 审批策略），
  // 每多一条 Promise 链就多几拍微任务 —— 原来只等 4 拍，合并后不够了。
  for (var s = 0; s < 16; s++) { await tick(); }
  var pv = document.getElementById('settingsContent').innerHTML;
  ok(pv.indexOf('插件（2）') >= 0, '插件面板列出了 2 个插件');
  ok(pv.indexOf('工具（1）') >= 0 && pv.indexOf('技能（1）') >= 0,
     '★ 按后端给的 kinds 中文名分组');
  ok(pv.indexOf('web_search') >= 0 && pv.indexOf('pdf') >= 0, '每个插件都列出来了');
  ok(pv.indexOf('内置') >= 0 && pv.indexOf('外置') >= 0, '★ 标了来源（内置 / 外置）');
  ok(pv.indexOf('1.0') >= 0, '标了版本');
  ok(pv.indexOf('配置文件缺失') >= 0 && pv.indexOf('set-err') >= 0, '★ 出错的插件红字显示原因');
  ok(pv.indexOf('App.togglePlugin(') >= 0 && pv.indexOf('type="checkbox"') >= 0, '★ 每个插件一个开关');
  ok(pv.indexOf('App.reloadPlugins()') >= 0, '★ 顶部有「重新加载插件」');
  ok(pv.indexOf('id="pluginDevMode"') >= 0, '★ 有「插件开发模式」开关');
  ok(pv.indexOf('id="pfId"') < 0, '开发模式没开时不显示生成骨架的表单');

  // 开关：成功后就地更新
  globalThis.__fetchLog = [];
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/enable') > 0 || u.indexOf('/disable') > 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, id: 'pdf', enabled: true }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
  };
  var swEl = { checked: true, disabled: false };
  App.togglePlugin('pdf', true, swEl);
  for (var t1 = 0; t1 < 4; t1++) { await tick(); }
  var call = globalThis.__fetchLog.filter(function(c) { return c.url.indexOf('/api/plugins/pdf/enable') >= 0; })[0];
  ok(!!call && call.method === 'POST', '★ 打开开关 → POST /api/plugins/pdf/enable', call ? call.url : '');
  ok(swEl.disabled === false, '请求结束后开关恢复可点');
  ok(document.getElementById('pluginTabMsg').textContent.indexOf('已启用') >= 0, '成功后给出提示');

  // 开关：失败回滚
  globalThis.__fetchHandler = function(u) {
    if (u.indexOf('/disable') > 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: false, error: '插件正在使用中' }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
  };
  var sw2 = { checked: false, disabled: false };
  App.togglePlugin('pdf', false, sw2);
  for (var t2 = 0; t2 < 8; t2++) { await tick(); }
  ok(sw2.checked === true, '★ 关开关失败 → 开关自动拨回去（不会假装成功）', String(sw2.checked));
  ok(document.getElementById('pluginTabMsg').textContent.indexOf('插件正在使用中') >= 0,
     '★ 失败原因写在面板上', document.getElementById('pluginTabMsg').textContent);

  // 开发模式 + 生成骨架
  console.log('--- 设置 → 插件：开发模式 / 生成骨架 ---');
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/api/plugins/dev-mode') >= 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, enabled: true }) };
    }
    if (u.indexOf('/api/plugins/scaffold') >= 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, path: 'C:\\plugins\\my_tool\\MyTool.java' }) };
    }
    if (u.indexOf('/api/plugins/reload') >= 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, count: 3 }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, plugins: [], kinds: [] }) };
  };
  App.setPluginDevMode(true);
  for (var t3 = 0; t3 < 4; t3++) { await tick(); }
  var pv2 = document.getElementById('settingsContent').innerHTML;
  ok(pv2.indexOf('id="pfId"') >= 0 && pv2.indexOf('id="pfName"') >= 0 && pv2.indexOf('id="pfKind"') >= 0,
     '★ 开发模式打开后出现「生成插件骨架」表单（id / 名称 / 类型）');
  document.getElementById('pfId').value = 'my_tool';
  document.getElementById('pfName').value = '我的工具';
  document.getElementById('pfKind').value = 'TOOL';
  globalThis.__fetchLog = [];
  App.scaffoldPlugin();
  for (var t4 = 0; t4 < 6; t4++) { await tick(); }
  var sc = globalThis.__fetchLog.filter(function(c) { return c.url.indexOf('/api/plugins/scaffold') >= 0; })[0];
  ok(!!sc && sc.body.indexOf('my_tool') >= 0 && sc.body.indexOf('TOOL') >= 0,
     '★ 生成骨架带上 id / name / kind', sc ? sc.body : '');
  ok(document.getElementById('pfMsg').textContent.indexOf('C:\\plugins\\my_tool\\MyTool.java') >= 0,
     '★ 生成完把路径显示出来', document.getElementById('pfMsg').textContent);

  // 后端没就绪：显示"还没就绪"，不抛异常
  console.log('--- 设置 → 插件：后端没就绪 ---');
  globalThis.__fetchHandler = function() {
    return { ok: false, status: 404, json: () => Promise.resolve({ timestamp: 1, status: 404, error: 'Not Found' }) };
  };
  var threw2 = null;
  try { App.renderPluginsTab(); for (var t5 = 0; t5 < 14; t5++) { await tick(); } } catch (e) { threw2 = e; }
  ok(threw2 === null, '插件接口 404 时渲染不抛异常');
  ok(document.getElementById('settingsContent').innerHTML.indexOf('该功能的后端还没就绪') >= 0,
     '★ 插件面板显示「该功能的后端还没就绪」而不是整页报错');

  // ==================== 4. 技能（现在在插件管理的 SKILL 分组里） ====================
  console.log('--- 设置 → 插件管理 → 技能 ---');
  App.sessionId = 'sess-1';
  App.currentSettingsTab = 'plugins';
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/api/skills/active?sessionId=') === 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, skills: ['pdf'] }) };
    }
    if (u.indexOf('/api/skills') === 0 && (!opt || opt.method !== 'POST')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, skills: [
        { id: 'pdf', name: 'pdf', displayName: '读 PDF', description: '把 PDF 读成文字',
          whenToUse: '用户给了 pdf 时', enabled: true, builtin: true, source: 'BUILTIN' },
        { id: 'xlsx', name: 'xlsx', displayName: '表格', description: '读写 Excel',
          enabled: false, builtin: false, source: 'EXTERNAL', error: null }
      ] }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
  };
  try { App.renderPluginsTab(); } catch (e) { threw2 = e; }
  for (var v = 0; v < 16; v++) { await tick(); }
  var sv = document.getElementById('settingsContent').innerHTML;
  ok(sv.indexOf('读 PDF') >= 0 && sv.indexOf('表格') >= 0,
     '★ 技能列表在插件管理里出来了（名称 + 描述）');
  ok(sv.indexOf('内置') >= 0 && sv.indexOf('外置') >= 0, '技能也标了来源');
  ok(sv.indexOf('把 PDF 读成文字') >= 0, '显示了技能的 description');
  ok(sv.indexOf('App.toggleSkill(') >= 0, '★ 每个技能一个 enable/disable 开关');
  var pinnedHtml = document.getElementById('skillPinned').innerHTML;
  ok(sv.indexOf('id="skillPinned"') >= 0 && pinnedHtml.indexOf('读 PDF') >= 0,
     '★ 当前会话已指定的技能显示成标签（pdf）', pinnedHtml.slice(0, 120));
  ok(pinnedHtml.indexOf('App.unpinSkill(') >= 0, '已指定的技能标签可以点 × 移除', pinnedHtml.slice(0, 120));
  ok(sv.indexOf('App.pinSkill(') >= 0, '★ 未指定的技能有「指定使用」按钮');
  ok(sv.indexOf('插件管理') >= 0 || sv.indexOf('skillsInlineBox') >= 0,
     '★ 技能与插件开关在同一页里（不再有独立技能页签）');

  // 指定使用 → POST /api/skills/active
  globalThis.__fetchLog = [];
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/api/skills/active') === 0 && opt && opt.method === 'POST') {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    if (u.indexOf('/api/skills/active?sessionId=') === 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, skills: ['pdf', 'xlsx'] }) };
    }
    if (u.indexOf('/api/skills') === 0) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, skills: [
        { id: 'pdf', displayName: '读 PDF', enabled: true },
        { id: 'xlsx', displayName: '表格', enabled: false }
      ] }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
  };
  App.pinSkill('xlsx');
  for (var w = 0; w < 6; w++) { await tick(); }
  var post = globalThis.__fetchLog.filter(function(c) { return c.url.indexOf('/api/skills/active') >= 0 && c.method === 'POST'; })[0];
  ok(!!post, '★ 点「指定使用」→ POST /api/skills/active');
  ok(!!post && post.body.indexOf('sess-1') >= 0 && post.body.indexOf('xlsx') >= 0,
     '★ 请求带上 sessionId 和技能列表', post ? post.body : '');

  // 技能接口 404：同样优雅降级
  globalThis.__fetchHandler = function() {
    return { ok: false, status: 500, json: () => Promise.resolve({ status: 500, error: 'Internal Server Error' }) };
  };
  var threw3 = null;
  try { App.renderPluginsTab(); for (var w2 = 0; w2 < 14; w2++) { await tick(); } } catch (e) { threw3 = e; }
  ok(threw3 === null && document.getElementById('settingsContent').innerHTML.indexOf('该功能的后端还没就绪') >= 0,
     '★ 技能接口挂了 → 插件管理说「还没就绪」，不抛异常');

  // ==================== 5. 老契约兼容 ====================
  console.log('--- 兼容旧返回格式（success + data 数组）---');
  globalThis.__fetchHandler = function(u, opt) {
    if (u.indexOf('/api/plugins') === 0 && (!opt || opt.method !== 'POST')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ success: true, data: [
        { id: 'old_tool', name: 'old_tool', type: 'TOOL', description: '老接口返回的插件',
          version: '0.1', initialized: true }
      ] }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ success: true, data: [] }) };
  };
  App.renderPluginsTab();
  for (var z = 0; z < 16; z++) { await tick(); }
  var oldHtml = document.getElementById('settingsContent').innerHTML;
  ok(oldHtml.indexOf('old_tool') >= 0 && oldHtml.indexOf('工具（1）') >= 0,
     '★ 后端还是老的 {success,data:[...]} 格式时照样能列出插件', oldHtml.slice(0, 160));

  console.log('');
  if (__fails.length) {
    console.log('结果：有失败项 -> ' + __fails.join(' | '));
    process.exit(1);
  }
  console.log('结果：全部通过');
 } catch (e) {
  console.log('测试脚本抛异常: ' + (e && e.stack ? e.stack : e));
  console.log('结果：有失败项');
  process.exit(1);
 }
})();
'''

js_file = os.path.join(TMP, 'ui_behavior_check.js')
io.open(js_file, 'w', encoding='utf-8', newline='\n').write(PREAMBLE + app_js + TESTS)
if node:
    r = subprocess.run([node, js_file], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=180)
else:
    r = subprocess.run([sys.executable, js_file], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=180)
print(r.stdout.strip())
if r.stderr.strip():
    print('--- stderr ---')
    print(r.stderr.strip()[:1500])
behavior_ok = (r.returncode == 0)

# =====================================================================
# 3) 网络：真 GET 一次页面
# =====================================================================
print()
print('=' * 72)
print('3) 网络：本地起静态服务，真 GET 一次 /index.html')
print('=' * 72)

file_bytes = os.path.getsize(UI)
srv = None
status = None
body = b''
try:
    class Quiet(SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def __init__(self, *a, **k):
            super().__init__(*a, directory=WEB, **k)

    srv = ThreadingHTTPServer(('127.0.0.1', 0), Quiet)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    with urllib.request.urlopen('http://127.0.0.1:%d/index.html' % port, timeout=30) as resp:
        status = resp.status
        body = resp.read()
finally:
    if srv:
        srv.shutdown()
        srv.server_close()

check('★ GET /index.html → HTTP 200', status == 200, 'HTTP %s' % status)
check('★ 页面完整（字节数和文件一致，没被写坏/截断）', len(body) == file_bytes,
      'HTTP %d 字节 vs 文件 %d 字节' % (len(body), file_bytes))
text = body.decode('utf-8', 'replace')
check('页面里有主题属性与浮层（说明取到的是改造后的页面）',
      'data-theme' in text and 'mentionPopup' in text and 'settingsContent' in text)
check('页面没有明显截断（结尾是 </html>）', text.rstrip().endswith('</html>'))
check('中文没乱码（能按 UTF-8 解出"设置"）', '设置' in text)

# =====================================================================
print()
print('=' * 72)
print('汇总：静态 + 行为 + 网络，共 %d 条通过' % n_ok)
print('=' * 72)
sys.exit(0 if (ok_all and behavior_ok) else 1)
