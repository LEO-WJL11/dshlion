# -*- coding: utf-8 -*-
r"""回归：用户"把工具都跑一遍"时出现的那几个 ❌ 都修掉了。

用户那一跑（1.4.1，标准 + 极简各一遍）里的失败项：

  标准：word_count ❌（传的是目录）、line_count ❌（目录）、fetch_url ❌（超时）、
        git_branch ❌（删不存在的分支）、delete_file ❌（删已经没了的路径）
  极简：read_file ❌ / head_tail_file ❌ / word_count ❌（文件还没建）、
        execute_command ❌（退出码 128）、run_background ❌（第一次）

这些"失败"里大部分是工具**本来能办成这件事**却回了一句 ❌：
  · 统计工具给了目录 → 现在按目录累计统计（有用结果）
  · read/head 给了目录 → 现在列出目录内容
  · 删已经不存在的路径 / 删不存在的分支 → 现在幂等成功（"本来就没有"）
  · run_background 原来走 cmd + 相对 workdir 直接 new File → 现在 PowerShell + 工作区解析 + 排空输出
  · fetch_url 没有 User-Agent → 有些站点直接不响应（这就是它超时、http_get 却行）
所以这里逐条钉住：这些调用**必须返回成功**，而且内容要有用。
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

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8895
APP_PORT = 8911
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionidem')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    # ① 统计工具给目录（原来 ❌ "这是目录，不是文件"）
    [('word_count', {'path': 'sub'})],
    [('line_count', {'path': 'sub'})],
    # ② read_file / head_tail_file 给目录（原来 ❌ "不是普通文件"）
    [('read_file', {'path': 'sub'})],
    [('head_tail_file', {'path': 'sub', 'mode': 'head', 'lines': '3'})],
    # ③ 删已经不存在的路径（原来 ❌ "路径不存在"）
    [('delete_file', {'path': 'gone.txt'})],
    # ④ 找不到文件时要有"相近名字"提示（原来只有一句"文件不存在"）
    [('read_file', {'path': 'nope_note.txt'})],
    # ⑤ 删不存在的分支（原来 ❌）
    [('git_init', {'path': 'repo'})],
    [('git_branch', {'path': 'repo', 'action': 'delete', 'branch': 'nothing'})],
    # ⑥ 建已存在的分支（原来第二次 ❌）——先建一次（空仓库转 checkout -b），再建同名
    [('git_branch', {'path': 'repo', 'action': 'create', 'branch': 'dev'})],
    [('git_branch', {'path': 'repo', 'action': 'create', 'branch': 'dev'})],
    # ⑦ run_background：PowerShell 语法 + 相对 workdir（原来 ❌）
    [('run_background', {'command': 'Write-Output "bg-ok"', 'workdir': 'sub'})],
    [('run_background', {'command': '$i=0; while ($i -lt 2000) { Write-Output ("line-" + $i); $i++ }'})],
    # 有多个在跑时不给 pid：先把名单回给它（诚实），再指定 pid 停掉一个
    [('stop_background', {})],
    [('stop_background', {'pid': 'PLACEHOLDER_A'})],
    [('stop_background', {})],
    [('stop_background', {})],
    # ⑧ fetch_url 必须带 User-Agent（没 UA 时站点不响应 = 用户遇到的超时）
    [('fetch_url', {'url': 'http://127.0.0.1:%d/page' % MOCK_PORT})],
    # ⑨ 退出码非 0 但**有输出**时要说清楚（原来一律"命令执行失败"）
    [('execute_command', {'command': 'cmd /c "echo has-output & exit 5"'})],
    # ⑩ git 的 128（当前目录不是仓库）要有专门提示
    [('execute_command', {'command': 'git status'})],
]

SEEN = []
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
        if self.path.startswith('/__seen'):
            return self._json({'seen': SEEN, 'ua': SEEN_UA})
        if self.path.startswith('/__ua'):
            return self._json({'ua': SEEN_UA})
        if self.path.startswith('/page'):
            # 记下抓取时带的 UA（没 UA 就记空串）
            SEEN_UA.append(self.headers.get('User-Agent') or '')
            body = b'<html><body>hello-ua-ok</body></html>'
            self.send_response(200)
            self.send_header('Content-Type', 'text/html')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        tool_msgs = [m for m in msgs if m.get('role') == 'tool']
        if tool_msgs:
            SEEN.append([str(m.get('content') or '') for m in tool_msgs])
        done = len(tool_msgs)
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif done < len(ROUNDS):
            lines = []
            for nm, a in ROUNDS[done]:
                args = dict(a)
                for k, v in list(args.items()):
                    if v == 'PLACEHOLDER_A':
                        # 拿上一批结果里的第一个后台 pid 填进来（模拟"模型用对了 pid"）
                        prev = ' '.join(SEEN[-1]) if SEEN else ''
                        m = re.search(r'PID: ([0-9a-f]{8})', prev)
                        args[k] = m.group(1) if m else 'nope'
                lines.append(block(nm, args))
            content = '\n'.join(lines)
        else:
            content = '幂等测试完了。'
        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})


SEEN_UA = []


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


def req(url, method='GET', body=None, timeout=600):
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
print('用户实测报错的那几个工具，现在要能办成事')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(os.path.join(WS, 'sub'), exist_ok=True)
with open(os.path.join(WS, 'sub', 'a.txt'), 'w', encoding='utf-8') as f:
    f.write('one two three\nfour five\n')
with open(os.path.join(WS, 'note_file.txt'), 'w', encoding='utf-8') as f:
    f.write('note\n')

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

    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把报错的工具都试一遍',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=900)

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    rows = seen[-1] if seen else []
    print('  带回 %d 条工具结果：' % len(rows))
    for idx, content in enumerate(rows, 1):
        mark = '!!' if ('错误' in content or '失败' in content) else '  '
        print('    %2d %s %s' % (idx, mark, content.replace('\n', ' ')[:110]))

    def row(i):
        return rows[i - 1] if len(rows) >= i else ''

    def good(i):
        r = row(i)
        return bool(r) and '错误' not in r and '失败' not in r

    check('★ word_count 给目录 → 目录累计统计（不再 ❌）',
          good(1) and '目录累计' in row(1) and '行数' in row(1), row(1)[:90])
    check('★ line_count 给目录 → 目录累计统计（不再 ❌）',
          good(2) and '目录累计' in row(2) and '总行数' in row(2), row(2)[:90])
    check('★ read_file 给目录 → 列出目录内容（不再 ❌）',
          good(3) and 'a.txt' in row(3), row(3)[:90])
    check('★ head_tail_file 给目录 → 列出条目（不再 ❌）',
          good(4) and 'a.txt' in row(4), row(4)[:90])
    check('★ delete_file 删不存在的路径 → 幂等成功（不再 ❌）',
          good(5) and '本来就不存在' in row(5), row(5)[:90])
    check('★ 读不存在的文件会给出"相近的名字"',
          'note_file.txt' in row(6), row(6)[:120])
    check('★ git_branch 删不存在的分支 → 幂等成功（不再 ❌）',
          good(8) and '本来就不存在' in row(8), row(8)[:90])
    check('★ git_branch 重复建同名分支 → 成功（不再 ❌）',
          good(10) and ('已存在' in row(10) or '分支' in row(10)), row(10)[:90])
    check('★ run_background 支持相对 workdir + PowerShell 语法',
          good(11) and 'sub' in row(11), row(11)[:120])
    check('★ run_background 高输出量不会把子进程堵死', good(12), row(12)[:90])
    check('多个后台进程时不给 pid → 把名单回给模型（而不是瞎停一个）',
          '有多个后台进程在跑' in row(13), row(13)[:90])
    check('给了 pid 就能停', good(14) and '停止' in row(14), row(14)[:80])
    check('只剩一个时不给 pid 也能停', good(15) and '停止' in row(15), row(15)[:80])
    check('全停完之后再停 → 明说没有在跑的进程', '没有在跑的后台进程' in row(16), row(16)[:90])
    check('★ fetch_url 抓到了页面内容', good(17) and 'hello-ua-ok' in row(17), row(17)[:90])
    ua = (req('http://127.0.0.1:%d/__ua' % MOCK_PORT).get('ua') or [''])[-1]
    check('★ fetch_url 带上了 User-Agent（没 UA 是它原来超时的原因）',
          'Mozilla' in ua, ua[:60])
    check('★ 退出码非 0 但有输出时说明白了',
          '退出码 5' in row(18) and '有输出' in row(18), row(18)[:140])
    check('★ git 的 128 给了"当前目录不是仓库"的提示',
          '128' in row(19) and ('不是 git 仓库' in row(19) or 'git 仓库' in row(19)),
          row(19)[:140])
    check('任务正常收尾', '幂等测试完了' in str(c.get('data')), str(c.get('data'))[:60])
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
