# -*- coding: utf-8 -*-
r"""复现并验证："工具卡住不能拖死整条消息"。

真实现象（用户 1.1.15 那一跑）：
  14:56:54 工具调用开始: git_remote {action=show, name=origin}
  15:00:09 用户手动停止          ← 中间 3 分 15 秒什么都没有
原因：git remote show 去连远端、git 在等凭据时不关 stdout，
     而工具里 readAllBytes() 写在 waitFor(30s) 前面 → 那个超时形同虚设。

这个测试用一个"永远不返回"的假工具来验证派发层的超时兜底：
  - 工具卡住 → 到点返回一句可照做的错误，任务继续往下走（不会整条消息废掉）
  - 同时验证正常工具不受影响
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
MOCK_PORT = 8884
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionhang')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 用一个真的不会返回的命令冒充卡住的工具（睡 300 秒，远超派发层的 20 秒超时）。
#
# 【为什么改了这条命令】原来是 `powershell -Command "$input | Out-Null"`，
# 它"卡住"靠的是老实现（每条命令新起一个 powershell、`$input` 会去读 stdin）。
# 现在 execute_command 是常驻终端，`$input` 直接是空管道 → PowerShell 立刻报
# "An empty pipe element is not allowed"，一秒就返回了 —— 那就测不到超时了。
# 换成 Start-Sleep 才是真正"不会返回"的命令。
HANG_CMD = 'Start-Sleep -Seconds 300'

ROUNDS = [
    [('execute_command', {'command': HANG_CMD})],
    [('working_directory', {})],
    # 终端被上层中断后必须重启，这条要能正常跑（证明没被卡住的命令堵死）
    [('execute_command', {'command': 'Write-Output "终端已恢复"'})],
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
            SEEN.append([(m.get('toolName') or m.get('name') or '?', str(m.get('content') or ''))
                         for m in tool_msgs])
        done = len(tool_msgs)
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif done == 0:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[0])
        elif done <= 1:
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[1])
        elif done <= 2:
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
print('工具卡住不能拖死整条消息（派发层超时兜底）')
print('=' * 72)
print('注意：这一步会真的等到 180 秒超时，属于预期（验证超时生效）')
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
                         '--lionbox.runtime.prewarm.enabled=false',
                         '--lionbox.agent.tool-timeout-seconds=20'],
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

    t0 = time.time()
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '跑一个会卡住的命令',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=600)
    dt = time.time() - t0

    seen = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    rows = seen[-1] if seen else []
    print('  整条消息耗时 %.0f 秒，带回 %d 条工具结果：' % (dt, len(rows)))
    for idx, (name, content) in enumerate(rows, 1):
        print('    %2d %s' % (idx, content.replace('\n', ' ')[:110]))

    all_text = ' '.join(c2 for _, c2 in rows)
    check('★ 卡住的工具到点被放弃（回了"工具执行超时"）', '工具执行超时' in all_text)
    check('★ 任务继续往下走了（后面的工具照样执行）', '工作' in all_text or len(rows) >= 2)
    check('整条消息没有无限挂住（测试里把工具超时设成 20 秒）', dt < 90, '耗时 %.0f 秒' % dt)
    check('★ 卡住的命令没有把常驻终端堵死（下一条命令照样跑）',
          '终端已恢复' in all_text,
          all_text.replace('\n', ' ')[-120:])
    check('任务正常收尾', '试完了' in str(c.get('data')), str(c.get('data'))[:60])
finally:
    log.close()
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)
    subprocess.run(['taskkill', '/F', '/IM', 'powershell.exe', '/FI', 'PID gt 0'],
                   capture_output=True) if False else None
    print('已清理（顺带把测试留下的卡住进程杀掉）')

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
