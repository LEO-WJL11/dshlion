# -*- coding: utf-8 -*-
r"""回归测试：第 5 跑里的 5 个真 bug。

原文（用户会话记录）：
  read_file       ❌ 读取文件失败: Input length = 1            （GBK 文件用 UTF-8 严格解码炸了）
  move_file       ❌ 参数错误: 缺少必需参数: source             （模型写的是 src/dest）
  git_commit      ❌ nothing to commit                          （空仓库，正常；但顺手补了兜底身份）
  delete_file     ❌ 删除失败（部分内容删不掉）: …\git_test_repo （含 .git，Windows 只读文件）
  execute_command ❌ rmdir /s /q xxx → PowerShell 不认 /s /q
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
MOCK_PORT = 8888
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionfix5')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    # GBK 中文文件（用户机器上很常见）
    [('read_file', {'path': 'gbk.txt'}),
     ('word_count', {'path': 'gbk.txt'})],
    # 模型爱写的 src/dest 别名
    [('move_file', {'src': 'gbk.txt', 'dest': 'moved.txt'})],
    # cmd 语法（PowerShell 不认 /s /q）+ PowerShell 别名（cmd 不认 ls）
    [('execute_command', {'command': 'ls'}),
     ('execute_command', {'command': 'rmdir /s /q todelete'})],
    # 含只读文件的目录（模拟 .git）
    [('delete_file', {'path': 'readonly_dir', 'recursive': 'true'})],
    # 新仓库提交（没配 user.name/user.email 也不该失败）
    [('git_init', {'path': 'repo'}),
     ('create_file', {'path': 'repo/a.txt', 'content': 'hi'}),
     ('git_commit', {'path': 'repo', 'message': 'first', 'all': 'true'})],
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
        elif done <= 2:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[1])
        elif done <= 3:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[2])
        elif done <= 5:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[3])
        elif done <= 6:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[4])
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


def llama_count():
    out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq llama-server.exe', '/NH'],
                         capture_output=True, text=True).stdout
    return len(re.findall(r'llama-server\.exe', out, re.I))


print('=' * 72)
print('第 5 跑的 5 个真 bug')
print('=' * 72)
kill_port(APP_PORT)
# 【为什么先 attrib -R】这个用例会造只读文件（模拟 .git 里那种），而只读文件让
# shutil.rmtree 删不掉目录 —— 带 ignore_errors=True 时它**静默失败**，于是上一轮的
# readonly_dir 留在临时目录里，下一轮 makedirs(exist_ok=True) 复用它、写文件直接
# PermissionError（整份用例在第一行 fixture 就崩，看不出跟被测代码有什么关系）。
os.system('attrib -R -S -H "%s\\*" /S /D >nul 2>&1' % TMP)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
# GBK 编码的中文文件（模拟用户机器上 ANSI 存的文件）
with open(os.path.join(WS, 'gbk.txt'), 'wb') as f:
    f.write('中文内容测试\n第二行\n'.encode('gbk'))
# 一个含只读文件的目录（模拟 .git）
ro = os.path.join(WS, 'readonly_dir')
os.makedirs(ro, exist_ok=True)
with open(os.path.join(ro, 'pack.idx'), 'wb') as f:
    f.write(b'binary')
os.chmod(os.path.join(ro, 'pack.idx'), 0o444)
os.system('attrib +R "%s" >nul 2>&1' % os.path.join(ro, 'pack.idx'))
# 待删目录（给 rmdir /s /q 用）
os.makedirs(os.path.join(WS, 'todelete'), exist_ok=True)
with open(os.path.join(WS, 'todelete', 'x.txt'), 'w', encoding='utf-8') as f:
    f.write('x')
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

    print('重放这些调用……')
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把之前失败的工具再试一遍',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)

    results = req('http://127.0.0.1:%d/__results' % MOCK_PORT).get('results') or []
    rows = results[-1] if results else []
    print('  带回 %d 条工具结果：' % len(rows))
    fails = []
    for idx, (name, content) in enumerate(rows, 1):
        flat = content.replace('\n', ' ')[:90]
        bad = ('工具执行错误' in content) or ('工具执行异常' in content)
        print('    %2d %-16s %s %s' % (idx, name, '❌' if bad else '✅', flat))
        if bad:
            fails.append((name, flat))

    all_text = ' '.join(c for _, c in rows)
    check('★ 全部成功（GBK 文件、别名、cmd/PowerShell、只读目录、空仓库提交）',
          len(rows) >= 9 and not fails, '结果 %d 条，失败 %s' % (len(rows), fails))
    check('read_file 读 GBK 中文文件不再 "Input length = 1"',
          'Input length' not in all_text and ('中文内容测试' in all_text or '第二行' in all_text))
    check('word_count 也能读 GBK 文件', 'Input length' not in all_text)
    check('move_file 认 src/dest 别名', '缺少必需参数' not in all_text
          and os.path.isfile(os.path.join(WS, 'moved.txt')))
    check('execute_command 的 ls（PowerShell 别名）能跑',
          '不是内部或外部命令' not in all_text)
    check('execute_command 的 rmdir /s /q（cmd 语法）能跑',
          not os.path.exists(os.path.join(WS, 'todelete')))
    check('delete_file recursive 能删含只读文件的目录',
          not os.path.exists(os.path.join(WS, 'readonly_dir')))
    check('git_commit 在空仓库/无身份配置下也不报身份错误',
          'who you are' not in all_text and 'Please tell me' not in all_text)
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
