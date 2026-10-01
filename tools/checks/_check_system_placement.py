# -*- coding: utf-8 -*-
"""回归测试：发给模型的消息里，system 必须只在开头。

对应 2026-09-28 23:4x 的真实故障（用户看到任务当场断掉）：

    模型调用失败: HTTP 500 - {"error":{"message":"...
    {{- raise_exception('System message must be at the beginnin...
    Error: Jinja Exception: System message must be at the beginning."}}

llama-server 这个版本 `--jinja` **默认就是开的**，用的是 Qwen 模板；
模板遇到夹在中间的 system 消息会直接抛异常、服务端回 500。
harness 以前在跑的过程中往里塞 system 消息（残缺调用纠正、单工具限制、重复调用提醒），
所以一旦触发纠错，整条消息就废了。

现在：中途提示一律走 user 角色（内容带【系统提示】），buildMessages 还有一道兜底
（历史里的 system 不在开头就降级成 user）。

这个测试让假模型先回一个空响应（触发纠正提示），再回一个工具调用，最后给答案，
然后检查每一次请求的消息顺序。
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8895
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionsysplace')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

TOOL_CALL = '<tool_call>\n<function=working_directory>\n</function>\n</tool_call>'
SEEN = []
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
        if self.path.startswith('/__seen'):
            return self._json({'seen': SEEN})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        raw = self.rfile.read(n).decode('utf-8', 'replace')
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {}
        msgs = payload.get('messages') or []
        has_tool = any(m.get('role') == 'tool' for m in msgs)
        has_notice = any(m.get('role') == 'user' and '【系统提示】' in str(m.get('content')) for m in msgs)
        SEEN.append({
            'roles': [m.get('role') for m in msgs],
            'system_indexes': [i for i, m in enumerate(msgs) if m.get('role') == 'system'],
            'mid_notices': [m.get('content') for m in msgs[1:]
                            if m.get('role') == 'user' and '【系统提示】' in str(m.get('content'))],
            'tools_sent': bool(payload.get('tools')),
        })
        # 按"对话形状"决定回什么，而不是按调用次数 —— 因为会话标题生成也会异步打这个接口，
        # 用次数判断会错位（第一版测试就栽在这上面：标题请求把第 1 次占掉了）。
        if has_tool:
            content = '都做完了。'
        elif has_notice:
            content = TOOL_CALL                # 收到纠正提示后：正常调工具
        else:
            content = ''                       # 首轮故意空响应 → 触发纠正提示（中途提示）
        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})


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


def req(url, method='GET', body=None, timeout=240):
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


def llama_count():
    out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq llama-server.exe', '/NH'],
                         capture_output=True, text=True).stdout
    return len(re.findall(r'llama-server\.exe', out, re.I))


print('=' * 72)
print('system 消息位置回归（模板要求它只能在开头）')
print('=' * 72)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'))
os.makedirs(WS)
llama_baseline = llama_count()

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text',
               'customApi': {'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT,
                             'apiKey': 'sk-mock', 'model': 'mock-model'}}, f, ensure_ascii=False)

kill_port(APP_PORT)
log = open(LOG, 'w', encoding='utf-8', errors='replace')
proc = subprocess.Popen([real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
                         '--server.port=%d' % APP_PORT,
                         '--lionbox.runtime.auto-download=false',
                         # 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，开着审核
                         # 文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）
                         '--lionbox.change-review.enabled=false',
                         '--lionbox.runtime.prewarm.enabled=false'],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
try:
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            break
        except Exception:
            time.sleep(1)

    w = req(APP + '/api/workspaces', 'POST', {'path': WS})
    ws_id = (w.get('data') or {}).get('workspaceId') or (w.get('data') or {}).get('id')
    s = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid = (s.get('data') or {}).get('sessionId')

    print('发消息：故意触发「空响应纠正」+ 工具调用……')
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '帮我看看当前目录',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=240)

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    print('  模型被调用 %d 次' % len(seen))
    for i, s_ in enumerate(seen):
        print('    第%d次: roles=%s system位置=%s 中途提示=%d'
              % (i + 1, s_['roles'], s_['system_indexes'], len(s_['mid_notices'])))

    bad = [i + 1 for i, s_ in enumerate(seen) if s_['system_indexes'] not in ([], [0])]
    check('★ 每次请求的 system 消息都只在开头（否则模板会 500）', not bad,
          '违规的第 %s 次请求' % bad if bad else '全部合规')
    check('★ 中途提示确实出现过（走的是 user 角色）',
          any(s_['mid_notices'] for s_ in seen),
          '提示条数 ' + str(sum(len(s_['mid_notices']) for s_ in seen)))
    check('聊天接口没报错（没有 500）', c.get('success'), c.get('error'))
    check('任务正常收尾', '都做完了' in str(c.get('data')), str(c.get('data'))[:60])
    check('全程没新起 llama-server', llama_count() <= llama_baseline,
          '测试前 %d / 测试后 %d' % (llama_baseline, llama_count()))
finally:
    log.close()
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)
    print('已清理')

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
