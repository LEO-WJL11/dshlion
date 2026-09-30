# -*- coding: utf-8 -*-
r"""回归测试：数值参数以字符串形式给进来时，工具不能炸。

真实现象（用户装 1.1.8 后跑"把工具都调一遍"）：
  glob_files     ❌ 匹配失败: class java.lang.String cannot be cast to class java.lang.Number
  head_tail_file ❌ 读取失败: 同上
  directory_tree ❌ 生成目录树失败: 同上
原因：文本通道（本地模式默认）下 <parameter=lines>5</parameter> 解析出来是字符串 "5"，
而工具里写的是 ((Number) args.get("lines")).intValue()（12 个工具文件都这么取参数）。

做法：假模型（不占 GPU）把数值参数**一律写成字符串**，真工具去执行；
下一轮请求里会带上工具结果原文，断言里面没有错误、且确实干成了活。
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
MOCK_PORT = 8893
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lioncoerce')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    [('directory_tree', {'path': '.', 'maxDepth': '2'}),
     ('glob_files', {'path': '.', 'pattern': '*.txt', 'maxResults': '5'}),
     ('head_tail_file', {'path': 'note.txt', 'mode': 'head', 'lines': '2'})],
    [('modify_file', {'path': 'note.txt', 'operation': 'replace',
                      'startLine': '1', 'endLine': '1', 'content': '改过的第一行'}),
     ('read_file', {'path': 'note.txt', 'startLine': '1', 'endLine': '1'}),
     # 故意漏掉 mode：应当默认 head（模型经常漏这个参数）
     ('head_tail_file', {'path': 'note.txt', 'lines': '1'})],
]

SEEN_RESULTS = []      # 每次请求里带的 tool 结果原文
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
        elif done <= len(ROUNDS[0]):
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[1])
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
print('数值参数（字符串形式）不该把工具炸掉')
print('=' * 72)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'))
os.makedirs(WS)
with open(os.path.join(WS, 'note.txt'), 'w', encoding='utf-8') as f:
    f.write('第一行\n第二行\n第三行\n')
llama_baseline = llama_count()

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

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

    print('让假模型把数值参数写成字符串，交给真工具执行……')
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '试几个带数值参数的工具',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)

    results = req('http://127.0.0.1:%d/__results' % MOCK_PORT).get('results') or []
    rows = results[-1] if results else []
    print('  最后一轮请求里带回 %d 条工具结果：' % len(rows))
    bad = []
    for name, content in rows:
        flat = content.replace('\n', ' ')[:90]
        is_err = ('ClassCastException' in content) or content.lstrip().startswith('工具执行错误') \
            or '不能强转' in content
        print('    %-16s %s %s' % (name, '❌' if is_err else '✅', flat))
        if is_err:
            bad.append(name)

    all_text = ' '.join(c for _, c in rows)
    check('★ 六个工具都执行了，且没有一条 ClassCastException/缺参数', len(rows) == 6 and not bad,
          '结果 %d 条，失败 %s' % (len(rows), bad))
    check('★ directory_tree 真的生成了树（maxDepth="2" 被当成数字用了）',
          'note.txt' in all_text, all_text[:80])
    check('★ head_tail_file 真的读了文件（lines="2" 生效）', '第一行' in all_text)
    check('★ 漏掉 mode 也能读（默认 head，不再报缺少必需参数）',
          '缺少必需参数' not in all_text and '第一行' in all_text)
    check('★ modify_file 真的改了内容（startLine/endLine 生效）',
          '改过的第一行' in open(os.path.join(WS, 'note.txt'), encoding='utf-8').read())
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
