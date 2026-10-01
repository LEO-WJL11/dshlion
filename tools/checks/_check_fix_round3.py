# -*- coding: utf-8 -*-
r"""回归测试：用户第 3 跑里失败的 4 类问题。

原文（用户会话记录）：
  escape_string  ❌ 未知目标: C:\Users\Leo\Desktop\测试  /  未知目标: a"b'c  /  未知目标: test
                 （模型把"要转义的文本"塞进了 target 参数）
  git_init       ❌ Cannot run program "git" (in directory "…\.git_test"): CreateProcess error=267, 目录名称无效
  git_status     ❌ 同上 → 模型据此以为"本机未安装 git"
  delete_file    ❌ 目录不为空，无法删除（模型想清掉整棵 .git_test）
  timestamp      输出 %2026-%58-%29 %12:%9:%2（strftime 格式被当成 Java 模式拆坏了）
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
MOCK_PORT = 8890
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionfix2')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

ROUNDS = [
    # 模型那种"target 里放正文"的写法（原样复现）
    [('escape_string', {'action': 'escape', 'input': 'test', 'target': 'C:\\Users\\Leo\\Desktop\\测试'}),
     ('escape_string', {'action': 'escape', 'input': 'test', 'target': 'a"b\'c'}),
     # 正常写法也得对
     ('escape_string', {'action': 'escape', 'input': 'a & "c"', 'target': 'html'}),
     ('escape_string', {'action': 'escape', 'input': '{"k":"v"}', 'target': 'json'})],
    [('timestamp', {'format': '%Y-%m-%d %H:%M:%S'}),
     ('timestamp', {'format': 'yyyy-MM-dd'}),
     ('timestamp', {'format': 'unix'})],
    [('git_init', {'path': '.git_test'}),
     ('git_status', {'path': '.git_test'})],
    [('delete_file', {'path': '.git_test', 'recursive': 'true'})],
]

SEEN_RESULTS = []
ROUND_INDEX = 0          # 假模型发到第几轮了
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
        # 按"第几轮"发，别按"收到几条工具结果"发：
        # 一轮里几个调用现在由模型决定（"一轮最多 3 个"那个上限已经删了），
        # 再按条数推断轮次会错位 —— 实测把 timestamp 那一轮整个跳过，断言就假失败。
        global ROUND_INDEX
        if '会话标题生成器' in joined or '短标题' in joined:
            content = '标题'
        elif ROUND_INDEX < len(ROUNDS):
            content = '\n'.join(block(nm, a) for nm, a in ROUNDS[ROUND_INDEX])
            ROUND_INDEX += 1
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
print('第 3 跑里失败的工具：escape_string / timestamp / git_* / delete_file')
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
        flat = content.replace('\n', ' ')[:92]
        bad = ('工具执行错误' in content) or ('工具执行异常' in content) or ('未知目标' in content)
        print('    %2d %-16s %s %s' % (idx, name, '❌' if bad else '✅', flat))
        if bad:
            fails.append((name, flat))

    all_text = ' '.join(c for _, c in rows)
    check('★ 调用全部成功（4 类老错误都不再出现）', len(rows) >= 9 and not fails,
          '结果 %d 条，失败 %s' % (len(rows), fails))
    check('escape_string：正文被塞进 target 时不再"未知目标"（按 html 处理并说明）',
          '未知目标' not in all_text)
    check('escape_string：正常 html 转义仍然正确（a & "c" → a &amp; &quot;c&quot;）',
          '&amp;' in all_text and '&quot;' in all_text)
    check('timestamp：strftime 的 %Y-%m-%d %H:%M:%S 输出了真实时间（不是 %2026-%58-%29）',
          '%2026' not in all_text and bool(re.search(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', all_text)))
    check('git_init：自动建目录后成功（不再 error=267）',
          '267' not in all_text and '目录名称无效' not in all_text)
    check('git_status：在真实目录里跑（或给出"目录不存在/没装 git"的明确提示）',
          'Cannot run program' not in all_text)
    check('delete_file recursive：整棵目录树删掉了', not os.path.exists(os.path.join(WS, '.git_test')))
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
