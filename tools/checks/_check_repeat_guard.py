# -*- coding: utf-8 -*-
"""回归测试：模型陷入重复调用时，harness 必须自己停下。

对应 2026-09-28 那次"把所有工具都调用一遍"的真实现场：
  create_directory 连续成功 7 次（参数一模一样）；fetch_url 连续 45 次几乎都没结果；
  最后前端报「❌ 处理超时」（ChatController 的 10 分钟上限被耗光）。

现在的守卫：同样的「工具 + 参数」第 3 次起只提示不执行、第 5 次直接终止任务。
这个测试让假模型**永远**返回同一个调用，验证：
  1. 确实会执行前两次（合理重试不该被拦），
  2. 之后不再执行、转为提示，
  3. 第 5 次终止任务并给出明确交代，
  4. 模型调用次数是个位数（不是 40 次），且全程没占 GPU。
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
MOCK_PORT = 8896
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionrepeat')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 永远返回同一个调用（参数一模一样）
SAME_CALL = ('<tool_call>\n<function=working_directory>\n</function>\n</tool_call>')

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
            msgs = (json.loads(raw).get('messages') or [])
        except Exception:
            msgs = []
        SEEN.append({'roles': [m.get('role') for m in msgs],
                     # 【判据必须是"【系统提示】"这个注入标记本身，而且只看非 system 消息】
                     # 原来这里写的是 `'系统提示' in content`，把**系统提示词本身**也算进去了 ——
                     # 只要有任何一个工具描述里出现"系统提示词"这几个字（skill_load 的描述就写了
                     # "技能 id 见系统提示词的技能目录"），就会被误报成"插了 7 条提示"。
                     # 真正要防的是守卫往对话中间塞【系统提示】那种纠偏话术。
                     'hints': [str(m.get('content')) for m in msgs
                               if m.get('role') != 'system'
                               and '【系统提示】' in str(m.get('content'))],
                     'tool_results': [str(m.get('content')) for m in msgs
                                      if m.get('role') == 'tool']})
        # 同样一个调用回 REPEAT_TIMES 次，之后收尾（守卫已经删了，不能指望它来结束）
        done = sum(1 for m in msgs if m.get('role') == 'tool')
        content = SAME_CALL if done < REPEAT_TIMES else '六次都真的执行了，没有被拦。'
        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})


# 同一个调用重复几次（守卫删掉之后，由假模型自己数够次数收尾）
REPEAT_TIMES = 6


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
    out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq llama-server.exe', '/NH'],
                         capture_output=True, text=True).stdout
    return len(re.findall(r'llama-server\.exe', out, re.I))


print('=' * 72)
print('重复调用守卫回归（模型永远回同一个工具调用）')
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

    print('发消息，模型会永远回同一个 working_directory 调用……')
    t0 = time.time()
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把所有工具都调用一遍我测试下',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)
    cost = time.time() - t0

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    hints = [h for s_ in seen for h in s_.get('hints', [])]
    answer = str(c.get('data') or '')

    print('  模型被调用 %d 次，耗时 %.1f 秒' % (len(seen), cost))
    print('  最终答复: %s' % answer[:120].replace('\n', ' '))
    # 现在的要求：**不拦、不跳过、不终止**。同一个工具同参数连调 6 次，6 次全都要真执行。
    # 注意：SEEN 是"每次请求都把整段历史再存一份"，直接累加会虚高（1+2+…+6=21）。
    # 最后一次请求的历史才是完整的一份。
    last_results = (seen[-1].get('tool_results') if seen else []) or []
    ran = [c for c in last_results if c.strip() and '未执行' not in c]
    check('★ 同一个调用连来 %d 次，每一次都真的执行了（不再"这次没有执行"）'
          % REPEAT_TIMES, len(ran) == REPEAT_TIMES,
          '最后一次请求里有 %d 条工具结果，其中真执行了 %d 条' % (len(last_results), len(ran)))
    check('★ 没有再插"【系统提示】…完全相同的参数…"这类提示', not hints, '提示 %d 条' % len(hints))
    check('★ 任务没有被自动终止（答复里没有"已停止"）', '已停止' not in answer, answer[:80])
    check('★ 任务正常收尾', '六次都真的执行了' in answer, answer[:80])
    check('★ 没有拖到前端超时（10 分钟）', cost < 300, '%.1f 秒' % cost)
    check('全程没有新起 llama-server（没占 GPU）', llama_count() <= llama_baseline,
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
