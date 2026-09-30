# -*- coding: utf-8 -*-
r"""回归测试：execute_command 必须是"一个持续运行的终端"。

用户原话：
  "shell要是一个持续运行的终端，不是每次执行新开一个终端"

所以这里验的是**同一个 shell 进程一直在**：
  1. $PID 前后不变（不同进程 PID 必然不同）
  2. cd 到子目录后，下一次调用还在那个目录
  3. 变量/函数跨调用还在
  4. 多行脚本（if { } 换行）能跑
  5. 中文输出不乱码
  6. cmd 语法（dir /b）在终端里照样能跑
  7. 失败命令给出非 0 退出码（不是"假装成功"）
  8. 超时命令 → 报超时并重启终端（重启后目录回到工作区，说明真是被杀了重建）
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
MOCK_PORT = 8891
APP_PORT = 8907
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionshell')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    # 1 记住终端进程号
    [('execute_command', {'command': 'Write-Output "PID=$PID"'})],
    # 2 切到一个子目录（只有"持续终端"才留得住）
    [('execute_command', {'command': 'cd sub; Write-Output "现在在 $((Get-Location).Path)"'})],
    # 3 下一次调用还在那个目录吗？
    [('execute_command', {'command': 'Write-Output "还是:$((Get-Location).Path)"'})],
    # 4 变量跨调用
    [('execute_command', {'command': '$lionvar = "lion-42"; Write-Output "set-ok"'})],
    [('execute_command', {'command': 'Write-Output "var=$lionvar"'})],
    # 5 函数跨调用
    [('execute_command', {'command': 'function LionHi($n) { "hi $n" }; Write-Output "fn-ok"'})],
    [('execute_command', {'command': 'Write-Output (LionHi "box")'})],
    # 6 多行脚本（换行 + 大括号，按行读 stdin 的实现会在这里卡死）
    [('execute_command', {'command': 'if ($true) {\n  Write-Output "multi-line-ok"\n}\nforeach ($i in 1..2) { Write-Output "i=$i" }'})],
    # 7 中文
    [('execute_command', {'command': 'Write-Output "中文输出测试OK"'})],
    # 8 cmd 语法
    [('execute_command', {'command': 'dir /b'})],
    # 9 检查进程号还有没有变（最后一个正常命令）
    [('execute_command', {'command': 'Write-Output "PID2=$PID"'})],
    # 10 失败命令
    [('execute_command', {'command': 'cmd /c "exit 7"'})],
    # 11 超时命令（3 秒超时）→ 终端被杀并重建
    [('execute_command', {'command': 'Start-Sleep -Seconds 30', 'timeout': '3'})],
    # 12 重启后的新终端：目录应回到工作区（说明状态真没了 = 真重启了）
    [('execute_command', {'command': 'Write-Output "重启后:$((Get-Location).Path) PID3=$PID"'})],
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
            content = '终端测完了。'
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
print('execute_command = 持续运行的终端')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(os.path.join(WS, 'sub'), exist_ok=True)
with open(os.path.join(WS, 'sub', 'note.txt'), 'w', encoding='utf-8') as f:
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
            {'sessionId': sid, 'message': '测一下终端是不是持续运行的',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=900)

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    rows = seen[-1] if seen else []
    print('  带回 %d 条工具结果：' % len(rows))
    for idx, content in enumerate(rows, 1):
        print('    %2d %s' % (idx, content.replace('\n', ' ')[:110]))

    def row(i):
        return rows[i - 1] if len(rows) >= i else ''

    import re

    pid1 = re.search(r'PID=(\d+)', row(1))
    pid2 = re.search(r'PID2=(\d+)', row(11))
    pid3 = re.search(r'PID3=(\d+)', row(14))

    check('★ 第 1 条拿到 shell 进程号', bool(pid1), row(1)[:60])
    check('★ 第 11 条还是同一个进程（PID 不变 = 终端没重开）',
          bool(pid1 and pid2) and pid1.group(1) == pid2.group(1),
          'PID1=%s PID2=%s' % (pid1 and pid1.group(1), pid2 and pid2.group(1)))
    check('★ cd 到子目录后，下一次调用还在子目录（状态保住了）',
          'sub' in row(3) and '还是' in row(3), row(3)[:90])
    check('★ 变量跨调用还在（var=lion-42）', 'var=lion-42' in row(5), row(5)[:90])
    check('★ 函数跨调用还在（hi box）', 'hi box' in row(7), row(7)[:90])
    check('★ 多行脚本能跑（没有卡死）',
          'multi-line-ok' in row(8) and 'i=2' in row(8), row(8)[:120])
    check('★ 中文输出不乱码', '中文输出测试OK' in row(9), row(9)[:90])
    check('cmd 语法 dir /b 在终端里能跑', 'note.txt' in row(10), row(10)[:90])
    check('失败命令给非 0 退出码（不是假装成功）', '退出码: 7' in row(12), row(12)[:90])
    check('★ 超时命令报超时并说明重启终端',
          '超时' in row(13) and '重启' in row(13), row(13)[:120])
    check('★ 重启后是新终端（PID 变了、目录回到工作区）',
          bool(pid3) and bool(pid1) and pid3.group(1) != pid1.group(1)
          and os.path.basename(WS) in row(14),
          'PID3=%s  %s' % (pid3 and pid3.group(1), row(14)[:90]))
    check('任务正常收尾', '终端测完了' in str(c.get('data')), str(c.get('data'))[:60])
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
