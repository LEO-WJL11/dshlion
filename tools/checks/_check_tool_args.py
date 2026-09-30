# -*- coding: utf-8 -*-
r"""回归测试：参数是"字符串"时这些工具不能再炸。

用户原话："修复报错工具"。极简模式那一跑的 ❌ 有好几个不是工具逻辑错，
而是**文本通道下参数全是字符串**，工具里却硬转 Number：

  head_tail_file ❌ 读取失败: class java.lang.String cannot be cast to class java.lang.Number
  line_count ❌ / word_count ❌ / modify_file ❌ / timestamp ❌

所以这里每条工具都用**字符串参数**（就是模型真实发出来的样子）跑一遍。
另外补上两条实测踩过的：
  - git_branch 在**空仓库**里 create → 原来报 fatal: not a valid object name: 'master'
  - stop_background 没给 pid（模型手上没有）→ 原来必然 ❌
"""
import json
import os
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
APP_PORT = 8909
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionargs')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 每条工具都只给字符串参数（数字、布尔都写成字符串）
ROUNDS = [
    [('head_tail_file', {'path': 'note.txt', 'mode': 'tail', 'lines': '2'})],
    [('read_file', {'path': 'note.txt', 'offset': '2', 'limit': '2'})],
    [('word_count', {'path': 'note.txt'})],
    [('line_count', {'path': 'gbk.txt'})],
    [('modify_file', {'path': 'new.txt', 'operation': 'create', 'content': 'hello new'})],
    [('modify_file', {'path': 'new.txt', 'operation': 'replace', 'startLine': '1',
                      'endLine': '1', 'content': 'hello replaced'})],
    [('generate_uuid', {'count': '3'})],
    [('list_directory', {'path': '.', 'recursive': 'true', 'maxDepth': '2'})],
    [('glob_files', {'path': '.', 'pattern': '*.txt', 'maxResults': '5'})],
    [('search_in_files', {'path': '.', 'pattern': 'hello', 'useRegex': 'true', 'maxResults': '5'})],
    [('directory_tree', {'path': '.', 'maxDepth': '2'})],
    [('change_permissions', {'path': 'note.txt', 'readable': 'true', 'writable': 'true',
                             'executable': 'false'})],
    [('delete_file', {'path': 'doomed', 'recursive': 'true'})],
    [('git_init', {'path': 'repo', 'bare': 'false'})],
    # 空仓库里建分支（原来 fatal: not a valid object name: 'master'）
    [('git_branch', {'path': 'repo', 'action': 'create', 'branch': 'dev'})],
    [('git_log', {'path': 'repo', 'count': '2'})],
    [('git_diff', {'path': 'repo', 'cached': 'true'})],
    # 后台进程：启动 → 不给 pid 直接停（模型手上常常没有 pid）
    [('run_background', {'command': 'Start-Sleep -Seconds 60'})],
    [('stop_background', {})],
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
            return self._json({'seen': SEEN})
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
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[done])
        else:
            content = '参数测完了。'
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
print('工具参数（字符串）不再炸 + 空仓库建分支 + 后台停进程')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
os.makedirs(os.path.join(WS, 'doomed', 'inner'), exist_ok=True)
with open(os.path.join(WS, 'note.txt'), 'w', encoding='utf-8') as f:
    f.write('line1\nline2\nline3\n')
with open(os.path.join(WS, 'gbk.txt'), 'wb') as f:      # GBK 编码的中文文件
    f.write('中文内容\n第二行\n'.encode('gbk'))
with open(os.path.join(WS, 'doomed', 'inner', 'x.txt'), 'w', encoding='utf-8') as f:
    f.write('x')

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
            {'sessionId': sid, 'message': '每个工具都用字符串参数试一遍',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=900)

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    rows = seen[-1] if seen else []
    print('  带回 %d 条工具结果：' % len(rows))
    for idx, content in enumerate(rows, 1):
        mark = '!!' if ('错误' in content or 'Exception' in content or '失败' in content) else '  '
        print('    %2d %s %s' % (idx, mark, content.replace('\n', ' ')[:110]))

    def row(i):
        return rows[i - 1] if len(rows) >= i else ''

    def good(i):
        """这一条既没报错，也不是空结果"""
        r = row(i)
        return bool(r) and '错误' not in r and '失败' not in r and 'Exception' not in r

    check('★ head_tail_file 的 lines="2"（字符串）不再 ClassCastException',
          good(1) and 'line2' in row(1) and 'line3' in row(1), row(1)[:80])
    check('★ read_file 的 offset/limit 字符串能用', good(2) and 'line2' in row(2), row(2)[:80])
    check('word_count 正常（字符串参数）', good(3) and '行数' in row(3), row(3)[:80])
    check('★ line_count 能读 GBK 中文文件（不再是 MalformedInput）',
          good(4) and '2' in row(4), row(4)[:80])
    check('★ modify_file operation=create 能建文件', good(5) and os.path.exists(
        os.path.join(WS, 'new.txt')), row(5)[:80])
    check('★ modify_file 的 startLine="1"（字符串）能替换',
          good(6) and 'replaced' in open(os.path.join(WS, 'new.txt'), encoding='utf-8').read(),
          row(6)[:80])
    check('★ generate_uuid count="3" 出 3 个', good(7) and row(7).count('-') >= 9, row(7)[:80])
    check('list_directory 的 recursive/maxDepth 字符串能用', good(8), row(8)[:80])
    check('glob_files 的 maxResults 字符串能用', good(9) and 'note.txt' in row(9), row(9)[:80])
    check('search_in_files 的 useRegex/maxResults 字符串能用',
          good(10) and '匹配' in row(10), row(10)[:80])
    check('directory_tree 的 maxDepth 字符串能用', good(11) and 'note.txt' in row(11), row(11)[:80])
    check('change_permissions 的布尔字符串能用', good(12), row(12)[:80])
    check('delete_file 的 recursive="true" 真删掉了目录',
          good(13) and not os.path.exists(os.path.join(WS, 'doomed')), row(13)[:80])
    check('git_init 的 bare="false" 能用', good(14), row(14)[:80])
    check('★ 空仓库里 git_branch create 不再报 "not a valid object name"',
          'not a valid object name' not in row(15) and ('dev' in row(15) or '分支' in row(15)),
          row(15)[:120])
    check('git_log 的 count 字符串能用（空仓库给友好提示）', good(16), row(16)[:80])
    check('git_diff 的 cached="true" 能用', good(17), row(17)[:80])
    pid_row = row(18)
    check('run_background 起来了', 'pid' in pid_row.lower() or '启动' in pid_row, pid_row[:80])
    check('★ stop_background 不给 pid 也能停掉（不再必然 ❌）',
          good(19) and '停止' in row(19), row(19)[:80])
    check('任务正常收尾', '参数测完了' in str(c.get('data')), str(c.get('data'))[:60])
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
