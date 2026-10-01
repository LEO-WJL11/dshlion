# -*- coding: utf-8 -*-
r"""验证：改中文文件时**编码不被悄悄改掉**。

前一版只做了"读"的容错（UTF-8 → GBK），但写回还是 UTF-8：
记事本存的 ANSI/GBK 文件被 modify_file 改一次就变成 UTF-8。
这个测试用真实 GBK 文件走一遍 read/append/modify/write，然后按字节检查编码有没有变。
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
MOCK_PORT = 8882
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_liongbk')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    [('read_file', {'path': 'gbk.txt'}),
     ('append_file', {'path': 'gbk.txt', 'content': '追加的中文'})],
    [('modify_file', {'path': 'gbk.txt', 'operation': 'replace',
                      'startLine': '1', 'endLine': '1', 'content': '改过的第一行'})],
    [('write_file', {'path': 'gbk.txt', 'content': '整体重写的中文内容'})],
]

SEEN = []
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


def is_gbk(path):
    """按字节判断：能用 GBK 解出来、且按 UTF-8 严格解会失败 → 这就是 GBK。"""
    raw = open(path, 'rb').read()
    try:
        raw.decode('utf-8')
        return False                     # 纯 UTF-8（含纯 ASCII）
    except UnicodeDecodeError:
        pass
    try:
        raw.decode('gbk')
        return True
    except UnicodeDecodeError:
        return None


def read_any(path):
    raw = open(path, 'rb').read()
    for enc in ('utf-8', 'gbk'):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode('utf-8', 'replace')


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
        if self.path.startswith('/__seen'):
            return self._json({'seen': SEEN})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        tool_msgs = [m for m in msgs if m.get('role') == 'tool']
        if tool_msgs:
            SEEN.append([(m.get('toolName') or m.get('name') or '?', str(m.get('content') or ''))
                         for m in tool_msgs])
        done = len(tool_msgs)
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif done < len(ROUNDS[0]):
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[0])
        elif done <= len(ROUNDS[0]) + 1 and done > len(ROUNDS[0]):
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[1])
        elif done <= len(ROUNDS[0]) + 2:
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
print('GBK 中文文件：读得对，改完编码也不变')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
gbk_file = os.path.join(WS, 'gbk.txt')
with open(gbk_file, 'wb') as f:
    f.write('原始第一行\n第二行\n'.encode('gbk'))
orig_is_gbk = is_gbk(gbk_file)
check('测试文件确实是 GBK 编码（不是 UTF-8）', orig_is_gbk is True, str(orig_is_gbk))

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

    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '读一个中文文件并改它',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    rows = seen[-1] if seen else []
    print('  带回 %d 条工具结果：' % len(rows))
    for idx, (name, content) in enumerate(rows, 1):
        print('    %2d %s' % (idx, content.replace('\n', ' ')[:80]))

    all_text = ' '.join(c2 for _, c2 in rows)
    final = read_any(gbk_file)
    print('  文件最终内容: %s' % final.replace('\n', ' | ')[:80])
    print('  文件最终编码: %s' % ('GBK' if is_gbk(gbk_file) else 'UTF-8'))

    check('★ 读 GBK 文件没有报错，内容正确', '原始第一行' in all_text and 'Input length' not in all_text)
    check('★ 追加后仍然是 GBK（没被改成 UTF-8）', is_gbk(gbk_file) is True)
    check('★ modify_file 之后仍然是 GBK', is_gbk(gbk_file) is True)
    check('★ write_file 覆盖之后仍然是 GBK', is_gbk(gbk_file) is True)
    check('★ 内容确实被改了（中文没乱）', '整体重写的中文内容' in final, final[:60])
    check('任务正常收尾', '试完了' in str(c.get('data')), str(c.get('data'))[:50])
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
