# -*- coding: utf-8 -*-
r"""回归测试：用户那一跑里失败过的工具，现在都得能用。

清单（错误原文来自用户会话记录）：
  string_utils   ❌ 未知操作: uppercase / to_uppercase / lowercase / capitalize / split / replace / to_lower
  number_convert ❌ 转换失败: class java.lang.String cannot be cast to class java.lang.Number
  yaml_process   ❌ 缺少必需参数: arguments（schema 声明的是 input —— 工具自己读错了参数名）
  git_stash      ❌ 未知操作: save / stash
  execute_command ❌ 'ls' 不是内部或外部命令（Windows 上原来走 cmd）
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
MOCK_PORT = 8892
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_liontools')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 数值参数一律写成字符串（文本通道就是这样），动作名故意用模型爱写的别名
ROUNDS = [
    [('string_utils', {'input': 'hello', 'action': 'uppercase'}),
     ('string_utils', {'input': 'WORLD', 'action': 'to_lower'}),
     ('string_utils', {'input': '  x  ', 'action': 'trim'})],
    [('number_convert', {'value': '10', 'fromBase': '10', 'toBase': '2'}),
     ('yaml_process', {'input': 'a: 1\nb: [2, 3]\n'}),
     ('execute_command', {'command': 'ls'})],
    [('git_init', {'path': '.'}),
     ('git_stash', {'path': '.', 'action': 'save'}),
     ('git_stash', {'path': '.', 'action': 'list'})],
]

SEEN_RESULTS = []
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


def block(name, args):
    body = ''.join('<parameter=%s>%s</parameter>\n' % (k, v) for k, v in args.items())
    return '<tool_call>\n<function=%s>\n%s</function>\n</tool_call>' % (name, body)


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
        if self.path.startswith('/__results'):
            return self._json({'results': SEEN_RESULTS})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        tool_msgs = [m for m in msgs if m.get('role') == 'tool']
        if tool_msgs:
            SEEN_RESULTS.append([(m.get('toolName') or m.get('name') or '?', str(m.get('content') or ''))
                                 for m in tool_msgs])
        done = len(tool_msgs)
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif done == 0:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[0])
        elif done <= 3:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[1])
        elif done <= 6:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[2])
        else:
            content = '这些都试完了。'
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


def llama_count():
    out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq llama-server.exe', '/NH'],
                         capture_output=True, text=True).stdout
    return len(re.findall(r'llama-server\.exe', out, re.I))


print('=' * 72)
print('用户那一跑里失败过的工具，现在都得能用')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
llama_baseline = llama_count()

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

    print('把用户失败过的调用重放一遍（动作名用他遇到的那些别名）……')
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把之前失败的工具再试一遍',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)

    results = req('http://127.0.0.1:%d/__results' % MOCK_PORT).get('results') or []
    rows = results[-1] if results else []
    print('  带回 %d 条工具结果：' % len(rows))
    fails = []
    for name, content in rows:
        flat = content.replace('\n', ' ')[:95]
        bad = ('工具执行错误' in content) or ('工具执行异常' in content) or ('Exception' in content)
        print('    %-18s %s %s' % (name, '❌' if bad else '✅', flat))
        if bad:
            fails.append((name, flat))

    check('★ 9 次调用全部成功（用户那次这 5 类错误一个都不该再出现）',
          len(rows) == 9 and not fails, '失败: %s' % fails)
    all_text = ' '.join(c for _, c in rows)
    check('string_utils 别名生效（uppercase/to_lower/trim 都认）', 'HELLO' in all_text and 'world' in all_text)
    check('number_convert 数值参数不再 ClassCast（10 进制 10 → 2 进制 1010）', '1010' in all_text)
    check('yaml_process 不再报"缺少必需参数: arguments"', '缺少必需参数' not in all_text and ('a' in all_text))
    check('git_stash save/list 别名生效', '未知操作' not in all_text)
    check('execute_command 走 PowerShell（ls 不再"不是内部或外部命令"）',
          '不是内部或外部命令' not in all_text and 'note' not in all_text)
    check('任务正常收尾', '试完了' in str(c.get('data')), str(c.get('data'))[:60])
    check('全程没新起 llama-server', llama_count() <= llama_baseline)
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
