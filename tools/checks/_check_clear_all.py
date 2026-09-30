# -*- coding: utf-8 -*-
r"""验证"清空所有对话"：
  后端：DELETE /api/sessions 真的把会话和历史都删掉（工作区保留）
  界面：按钮存在、带两次确认、点击后发 DELETE 并刷新列表
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
UI = os.path.join(ROOT, 'web', 'index.html')
MOCK_PORT = 8880
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionclear')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def _json(self, obj):
        body = json.dumps(obj).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        self.rfile.read(n)
        return self._json({'id': 'c', 'object': 'chat.completion', 'created': int(time.time()),
                           'model': 'mock',
                           'choices': [{'index': 0, 'finish_reason': 'stop',
                                        'message': {'role': 'assistant', 'content': '好'}}],
                           'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}})


def real_java():
    home = os.environ.get('JAVA_HOME')
    if home and os.path.isfile(os.path.join(home, 'bin', 'java.exe')):
        return os.path.join(home, 'bin', 'java.exe')
    for base in (r'C:\Program Files\Java', r'C:\Program Files\Eclipse Adoptium'):
        if os.path.isdir(base):
            for d in sorted(os.listdir(base)):
                p = os.path.join(base, d, 'bin', 'java.exe')
                if os.path.isfile(p):
                    return p
    return shutil.which('java') or 'java'


def req(url, method='GET', body=None, timeout=120):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'replace'))


def kill_port(port):
    out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)

    # 【坑】kill 完要等端口真的空出来：不等的话下一个套件起 app 会绑定失败，
    # 但请求又打到上一个没退干净的进程上 —— 表现就是"单跑能过、连跑就挂"。
    deadline = time.time() + 20
    while time.time() < deadline:
        rows = subprocess.run(['netstat', '-ano', '-p', 'TCP'],
                              capture_output=True, text=True).stdout.splitlines()
        busy = any(len(x.split()) >= 5 and x.split()[1].endswith(':' + str(port))
                   and x.split()[3] == 'LISTENING' for x in rows)
        if not busy:
            return
        time.sleep(0.5)


print('=' * 72)
print('清空所有对话')
print('=' * 72)

# ---------- 1. 界面：按钮 + 两次确认 + 发 DELETE ----------
html = io.open(UI, encoding='utf-8', newline='').read()
app_js = re.search(r'(?s)<script>(.*?)</script>', html).group(1)
check('界面里有"清空"按钮（侧栏对话标题右边）', 'clearAllSessions' in html and '🗑 清空' in html)
check('按钮接了 App.deleteAllSessions', 'App.deleteAllSessions' in html)

PREAMBLE = r'''
const __store = {};
globalThis.localStorage = { getItem: k => (k in __store ? __store[k] : null),
  setItem: (k,v) => { __store[k] = String(v); }, removeItem: k => { delete __store[k]; } };
const __els = {};
function __mkEl(id) { return { id, innerHTML:'', textContent:'', value:'', checked:false, style:{}, dataset:{},
  disabled:false, classList:{add(){},remove(){},toggle(){},contains(){return false;}}, appendChild(){},
  removeChild(){}, setAttribute(){}, removeAttribute(){}, addEventListener(){}, focus(){}, blur(){}, click(){},
  querySelector(){return null;}, querySelectorAll(){return [];}, insertAdjacentHTML(){}, remove(){} }; }
globalThis.document = { getElementById(id){ return __els[id] || (__els[id] = __mkEl(id)); },
  querySelector(){return null;}, querySelectorAll(){return [];}, createElement(t){return __mkEl(t);},
  addEventListener(){}, body: __mkEl('body'), documentElement: __mkEl('html'), execCommand(){return true;} };
globalThis.window = globalThis; globalThis.window.addEventListener = function(){};
globalThis.location = { href:'/', reload(){} };
globalThis.navigator = { clipboard:{ writeText: () => Promise.resolve() } };
globalThis.__alerts = []; globalThis.__confirms = [];
globalThis.alert = m => { globalThis.__alerts.push(String(m)); };
globalThis.confirm = m => { globalThis.__confirms.push(String(m)); return true; };
globalThis.prompt = () => 'x';
globalThis.setTimeout = () => 0; globalThis.clearTimeout = function(){}; globalThis.setInterval = () => 0;
globalThis.clearInterval = function(){};
globalThis.EventSource = function(){ this.close = function(){}; this.addEventListener = function(){}; };
globalThis.marked = { parse: s => s }; globalThis.hljs = { highlightElement(){}, highlight(){ return {value:''}; } };
globalThis.__calls = [];
globalThis.fetch = function(url, opt) {
  globalThis.__calls.push({ url: String(url), method: (opt && opt.method) || 'GET' });
  var data = { success: true, data: { total: 3, deleted: 3, failed: 0 } };
  if (String(url).indexOf('/api/sessions') === 0 && (!opt || opt.method !== 'DELETE')) { data.data = []; }
  return Promise.resolve({ json: () => Promise.resolve(data) });
};
var __fails = [];
function ok(c, label, extra) { if (c) console.log('  [OK]   ' + label);
  else { console.log('  [FAIL] ' + label + (extra ? '  ' + extra : '')); __fails.push(label); } }
function tick(){ return new Promise(r => r()); }
'''

TESTS = r'''
(async function() {
 try {
  App.sessionId = 'sess-1';
  App.sessionsData = [{sessionId:'a',workspaceId:'C:\\w',name:'甲'},
                      {sessionId:'b',workspaceId:'C:\\w',name:'乙'},
                      {sessionId:'c',workspaceId:'C:\\w',name:'丙'}];
  globalThis.__confirms = []; globalThis.__calls = [];
  App.deleteAllSessions();
  for (var __i = 0; __i < 6; __i++) { await tick(); }   // 假环境 setTimeout 不触发，多等几轮 promise
  ok(globalThis.__confirms.length === 2, '删之前问了两次（防手滑）',
     '实际问了 ' + globalThis.__confirms.length + ' 次');
  ok(globalThis.__confirms[0].indexOf('3') >= 0, '第一次确认里写清了要删几个（3）');
  ok(globalThis.__confirms[1].indexOf('无法恢复') >= 0, '第二次确认提醒不可恢复');
  var del = globalThis.__calls.filter(function(c) { return c.method === 'DELETE'; })[0];
  ok(!!del && del.url.indexOf('/api/sessions') >= 0, '真的发了 DELETE /api/sessions');
  ok(globalThis.__calls.some(function(c) { return c.url.indexOf('/api/sessions') >= 0 && c.method === 'GET'; }),
     '删完重新拉了对话列表');
  ok(App.deleteAllSessions.toString().indexOf('已清空') >= 0,
     '清空后有"已清空 N 个对话"的提示（假环境 setTimeout 不触发，这里查源码）');
  if (__fails.length) { console.log('结果：有失败项 -> ' + __fails.join(' | ')); process.exit(1); }
  console.log('结果：全部通过');
 } catch (e) {
  console.log('测试脚本抛异常: ' + (e && e.stack ? e.stack : e));
  console.log('结果：有失败项');
  process.exit(1);
 }
})();
'''

with tempfile.TemporaryDirectory() as tmp:
    js = os.path.join(tmp, 'clear_all_check.js')
    io.open(js, 'w', encoding='utf-8', newline='\n').write(PREAMBLE + app_js + TESTS)
    r = subprocess.run(['node', js], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=120)
print('--- 界面（假 DOM 真跑一遍）---')
print(r.stdout.strip())
if r.stderr.strip():
    print(r.stderr.strip()[:600])
ui_ok = r.returncode == 0

# ---------- 2. 后端：DELETE /api/sessions 真删掉（工作区保留） ----------
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

log = open(LOG, 'w', encoding='utf-8', errors='replace')
proc = subprocess.Popen([real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
                         '--server.port=%d' % APP_PORT,
                         '--lionbox.runtime.auto-download=false',
                         '--lionbox.runtime.prewarm.enabled=false'],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
print('--- 后端 ---')
try:
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            break
        except Exception:
            time.sleep(1)

    w = req(APP + '/api/workspaces', 'POST', {'path': WS}).get('data') or {}
    ws_id = w.get('id') or w.get('workspaceId')
    sids = []
    for i in range(3):
        s = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'}).get('data') or {}
        sids.append(s.get('sessionId'))
        # 塞一条消息，验证历史文件也会被清掉
        req(APP + '/api/chat', 'POST', {'sessionId': s.get('sessionId'), 'message': '你好',
                                        'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=120)
    before = req(APP + '/api/sessions').get('data') or []
    mine = [s for s in before if s.get('workspaceId') == ws_id]
    check('先建了 3 个对话（它们在列表里）', len(mine) == 3, '实际 %d' % len(mine))

    conv_dir = os.path.join(HOME, '.lioncode', 'conversations')
    hist_before = len([f for f in os.listdir(conv_dir)]) if os.path.isdir(conv_dir) else 0
    print('  清空前：会话 %d 个，历史文件 %d 个' % (len(before), hist_before))

    res = req(APP + '/api/sessions', 'DELETE')
    check('★ 清空接口返回成功', res.get('success'), str(res.get('error'))[:80])
    data = res.get('data') or {}
    check('★ 报告删除数量正确（>=3）', (data.get('deleted') or 0) >= 3, str(data))

    after = req(APP + '/api/sessions').get('data') or []
    check('★ 会话列表真的空了', len(after) == 0, '还剩 %d 个' % len(after))
    hist_after = len([f for f in os.listdir(conv_dir)]) if os.path.isdir(conv_dir) else 0
    check('★ 磁盘上的对话历史文件也清了', hist_after == 0, '还剩 %d 个' % hist_after)

    ws_after = req(APP + '/api/workspaces').get('data') or []
    check('★ 工作区保留着（清空只删对话）', len(ws_after) >= 1, '%d 个工作区' % len(ws_after))

    new_s = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    check('清空后还能正常新建对话', new_s.get('success'))
    again = req(APP + '/api/sessions', 'DELETE')
    check('再清一次也不报错（幂等）', again.get('success'), str(again.get('data')))
finally:
    log.close()
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)

print('=' * 72)
print('结果：' + ('全部通过' if (ok_all and ui_ok) else '有失败项'))
sys.exit(0 if (ok_all and ui_ok) else 1)
