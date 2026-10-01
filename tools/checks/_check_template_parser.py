# -*- coding: utf-8 -*-
"""回归测试：模型吐 Qwen 模板原生工具调用时，harness 必须真的执行它。

针对 2026-09-28 23:03 的真实故障：
  模型输出「我先了解工作区环境，然后逐个调用工具做测试。<tool_call>
  <function=execute_command><parameter=command>ls -la && pwd</parameter></function></tool_call>」
  → 老解析器把它当 JSON 解析，报 "Unexpected character ('<')"，整轮工具调用作废，
    界面表现是"它说要调用工具，然后什么都没发生"。

做法：不用 GPU、不碰 8788。起一个假的 OpenAI 兼容接口（8897），第一次请求回上面那段原文，
第二次请求（harness 带着工具结果回来时）回一句最终答复。断言：假接口收到了第二次请求、
里面有 execute_command 的结果，且客户端拿到最终答复。
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
MOCK_PORT = 8897
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionparser')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 真实失败现场（原文照抄）
MODEL_CALL = ('我先了解工作区环境，然后逐个调用工具做测试。<tool_call>\n'
              '<function=execute_command>\n<parameter=command>\n'
              'echo parser-ok\n</parameter>\n</function>\n</tool_call>')

SEEN = []      # 每次请求的摘要
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
        roles = [m.get('role') for m in msgs]
        has_tool_result = any(m.get('role') == 'tool' for m in msgs)
        text = json.dumps(msgs, ensure_ascii=False)
        SEEN.append({'roles': roles, 'has_tool_result': has_tool_result,
                     'has_command': 'echo parser-ok' in text,
                     'tools_sent': bool(payload.get('tools')),
                     'stream': bool(payload.get('stream'))})
        if has_tool_result:
            content = '工具执行完成。'
        else:
            content = MODEL_CALL
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


def req(url, method='GET', body=None, timeout=180):
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
    """当前 llama-server 进程数（用户自己可能正在跑一个，所以只比较前后增量）"""
    out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq llama-server.exe', '/NH'],
                         capture_output=True, text=True).stdout
    return len(re.findall(r'llama-server\.exe', out, re.I))


print('=' * 72)
print('模板原生工具调用 <function=...> 端到端回归')
print('=' * 72)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'))
os.makedirs(WS)
llama_baseline = llama_count()
print('测试前 llama-server 进程数 = %d（用户自己的实例，测试不该改变它）' % llama_baseline)

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
print('假模型接口已启动: http://127.0.0.1:%d/v1' % MOCK_PORT)

# 预置配置：自定义 API + 文本工具通道（就是本地模型实际走的那条路）
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
    up = False
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            up = True
            break
        except Exception:
            time.sleep(1)
    check('应用已启动', up)
    if not up:
        raise SystemExit(1)

    w = req(APP + '/api/workspaces', 'POST', {'path': WS})
    ws_id = (w.get('data') or {}).get('workspaceId') or (w.get('data') or {}).get('id')
    check('工作区已创建', bool(ws_id), w.get('error'))
    s = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid = (s.get('data') or {}).get('sessionId')
    check('会话已创建', bool(sid), s.get('error'))

    print('')
    print('发一条消息，让模型回那段「模板原生工具调用」原文……')
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把所有工具都调用一遍我测试下',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=240)
    check('聊天接口返回成功', c.get('success'), c.get('error'))

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    for i, s in enumerate(seen):
        print('    第%d次请求: roles=%s 带工具结果=%s 带命令=%s 下发tools=%s'
              % (i + 1, s['roles'], s['has_tool_result'], s['has_command'], s['tools_sent']))
    tool_reqs = [s for s in seen if s['has_tool_result']]
    check('★ 有一次请求带着工具结果（说明工具真的执行完并回传了）', len(tool_reqs) >= 1,
          '共 %d 次请求' % len(seen))
    if tool_reqs:
        check('★ 那次请求里带着模型给的命令', tool_reqs[0]['has_command'])
    check('文本通道下没下发 tools（本地模型走的就是这条路）',
          not (seen[0]['tools_sent'] if seen else True))
    check('最终答复回到客户端', '工具执行完成' in str(c.get('data')), c.get('data'))
    check('全程没有新起 llama-server（没占 GPU）', llama_count() <= llama_baseline,
          '测试前 %d 个 / 测试后 %d 个' % (llama_baseline, llama_count()))
finally:
    log.close()
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)
    print('已清理：应用、假接口、%d 端口' % APP_PORT)

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
