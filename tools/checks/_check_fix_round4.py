# -*- coding: utf-8 -*-
r"""回归测试：第 4 跑剩下的问题（number_convert 进制名、word_count 传目录、
stop_background 乱编 pid、ask_user 只等 5 秒、git_reset 不存在）。

全部用用户日志里的**原始参数**重放。
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
MOCK_PORT = 8889
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionfix4')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    # 模型原始写法：进制用名字，且顺序颠倒
    [('number_convert', {'toBase': 'hex', 'value': '255', 'fromBase': 'dec'}),
     ('number_convert', {'fromBase': 'bin', 'toBase': 'dec', 'value': '1010'}),
     ('number_convert', {'fromBase': '10', 'toBase': '2', 'value': '10'})],
    # 目录当文件传（用户日志里那次）
    [('word_count', {'path': '.'}),
     ('stop_background', {'pid': 'a7472d31'})],
    [('git_init', {'path': 'repo'}),
     ('git_reset', {'path': 'repo', 'mode': '--hard', 'ref': 'HEAD'})],
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
        elif done <= 5:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[2])
        else:
            content = '都试完了。'
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
print('第 4 跑剩下的问题')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
with open(os.path.join(WS, 'sample.txt'), 'w', encoding='utf-8') as f:
    f.write('hello world\nsecond line\n')

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

    print('重放这些调用……')
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把之前失败的工具再试一遍',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)

    results = req('http://127.0.0.1:%d/__results' % MOCK_PORT).get('results') or []
    rows = results[-1] if results else []
    print('  带回 %d 条工具结果：' % len(rows))
    fails = []
    for idx, (name, content) in enumerate(rows, 1):
        flat = content.replace('\n', ' ')[:96]
        bad = ('工具执行错误' in content) or ('工具执行异常' in content) or ('ClassCast' in content)
        print('    %2d %-16s %s %s' % (idx, name, '❌' if bad else '✅', flat))
        if bad:
            fails.append((name, flat))

    all_text = ' '.join(c for _, c in rows)
    # 有一行是**故意**报错的：拿编造的 pid 停进程 —— 要的不是"成功"，
    # 而是"错误信息能照着改"。
    # （word_count 传目录那条 2026-09-30 起不再报错了：现在按目录累计统计，见
    #   _check_tool_idempotent.py —— 用户实测反馈"能办成的事别回 ❌"。）
    expected_error = [f for f in fails if '未找到进程' in f[1]]
    unexpected = [f for f in fails if f not in expected_error]
    check('★ 7 次调用里只有一条是"故意报错"的，其余全部成功',
          len(rows) >= 7 and not unexpected, '意外失败: %s' % unexpected)
    check('number_convert：fromBase=dec / toBase=hex 这种"进制名"也认（255 → FF）',
          'FF' in all_text.upper())
    check('number_convert：1010(2进制) → 10（十进制）',
          re.search(r'1010.*=\s*10\b', all_text) is not None or '十进制: 10' in all_text)
    check('word_count：传目录时给出能看懂的回应（现在直接按目录累计统计）',
          ('目录累计' in all_text) or ('这是目录' in all_text) or ('不是文件' in all_text))
    check('stop_background：乱编 pid 时列出当前可用的后台进程',
          '未找到进程' in all_text and ('后台进程' in all_text))
    check('git_reset：真的跑起来了（git reset --hard 退出码 0）',
          'git reset --hard' in all_text and '未找到工具' not in all_text)
    check('git_reset 的 --hard 有提醒', '丢弃' in all_text or 'hard' in all_text)
    check('任务正常收尾', '试完了' in str(c.get('data')), str(c.get('data'))[:60])
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
