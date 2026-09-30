# -*- coding: utf-8 -*-
r"""ask_user 端到端验证（假模型，不占 GPU）。

用户问"ask_user 好像没测到" —— 用假模型精确验证这条链路：
  模型要提问 → 问题出现在 /api/questions/pending → 我们代替用户回答
  → 回答作为工具结果回给模型 → 模型据此给出最终答复
另外验证：
  - options 会显示成按钮（pending 里带 options）
  - timeoutSeconds 下限 30 秒（模型填 5 秒会被夹住）
  - 不回答时到点会超时并回一句可读的说明
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
MOCK_PORT = 8886
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionask')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ASK_CALL = ('<tool_call>\n<function=ask_user>\n'
            '<parameter=question>你的项目叫什么名字？</parameter>\n'
            '<parameter=options>["LionBox", "别的名字"]</parameter>\n'
            '<parameter=timeoutSeconds>5</parameter>\n'
            '</function>\n</tool_call>')

SEEN_REQUESTS = []
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
            return self._json({'seen': SEEN_REQUESTS})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        tool_msgs = [str(m.get('content') or '') for m in msgs if m.get('role') == 'tool']
        SEEN_REQUESTS.append({'tools': len(payload.get('tools') or []), 'tool_results': tool_msgs})
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif not tool_msgs:
            content = '我需要先确认一下。\n' + ASK_CALL
        else:
            content = '收到你的回答：' + (tool_msgs[-1][:60] if tool_msgs else '') + '。任务继续。'
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


def req(url, method='GET', body=None, timeout=300):
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
print('ask_user 端到端（假模型 + 真问答接口）')
print('=' * 72)
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

    check('ask_user 已注册', 'ask_user' in json.dumps(req(APP + '/api/plugins')),
          '插件列表接口')
    pend0 = req(APP + '/api/questions/pending?sessionId=' + sid)
    check('没有提问时 pending 是空的', pend0.get('success') and pend0.get('data') is None,
          str(pend0.get('data'))[:60])

    print('发消息（模型会要求提问）……')
    result = {}

    def worker():
        try:
            result['resp'] = req(APP + '/api/chat', 'POST',
                                 {'sessionId': sid, 'message': '帮我起个项目名',
                                  'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=600)
        except Exception as e:
            result['error'] = str(e)

    th = threading.Thread(target=worker, daemon=True)
    th.start()

    # 等提问出现
    question = None
    for _ in range(60):
        p = req(APP + '/api/questions/pending?sessionId=' + sid, timeout=20)
        if p.get('success') and p.get('data'):
            question = p['data']
            break
        time.sleep(1)

    check('★ 模型提问后，问题出现在 /api/questions/pending', question is not None,
          (question or {}).get('question', ''))
    if question:
        print('     问题: %s' % question.get('question'))
        print('     选项: %s' % question.get('options'))
        print('     超时: %s 秒' % question.get('timeoutSeconds'))
        check('问题带上了模型给的选项', 'LionBox' in json.dumps(question.get('options') or []))
        check('★ timeoutSeconds 被夹到 ≥30 秒（模型填的是 5）',
              (question.get('timeoutSeconds') or 0) >= 30, str(question.get('timeoutSeconds')))
        ans = req(APP + '/api/questions/answer', 'POST',
                  {'questionId': question.get('id'), 'answer': 'LionBox'})
        check('提交回答成功', ans.get('success'), str(ans.get('error'))[:80])

    th.join(timeout=120)
    resp = result.get('resp') or {}
    check('消息跑完了（说明回答把工具放行了）', not th.is_alive() and not result.get('error'),
          result.get('error', '')[:80])
    check('接口返回成功', resp.get('success'), str(resp.get('error'))[:80])
    final = str(resp.get('data') or '')
    check('★ 模型拿到了回答（最终答复里含 LionBox）', 'LionBox' in final, final[:80])

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    answers = [r for r in seen if r['tool_results']]
    check('★ 回答是作为工具结果回给模型的',
          bool(answers) and any('LionBox' in t for r in answers for t in r['tool_results']),
          str(answers[-1]['tool_results'])[:80] if answers else '没有工具结果')
    pend2 = req(APP + '/api/questions/pending?sessionId=' + sid)
    check('跑完之后没有遗留待回答', pend2.get('success') and pend2.get('data') is None,
          str(pend2.get('data'))[:60])
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
