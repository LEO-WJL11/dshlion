# -*- coding: utf-8 -*-
r"""把用户实测坏掉的工具重跑一遍（真链路：假模型发工具调用 → 真执行 → 看结果）。

覆盖：
  1. git_diff 带 file 参数 —— 以前路径被当 revision 解析（fatal: ambiguous argument）
  2. git_remote add **不给 name** —— 以前直接报缺参数
  3. git_log 空仓库 —— 以前把 git 的 fatal 当成功吐回去
  4. git_commit 没有可提交的改动 —— 以前报错，模型反复重试触发拦截
  5. modify_file 只给 oldText（不给行号）—— 以前报"行号超出范围: 0"
  6. word_count 查一个非文本文件 —— 以前直接失败
  7. web_search —— 以前是占位实现（"需要配置搜索 API 密钥"）；
     现在用无头浏览器（Edge/Chrome）搜，结果里写明走的哪条路
  8. 顺便验证解析器：<function=名字>{JSON}</function> 这种写法也能带上参数

每个工具**单独一轮对话**：这样最后一条工具结果一定属于这一轮要试的那个工具，
不会因为消息里没有工具名字段而分不清谁是谁。
"""
import io
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
MOCK_PORT = 8885
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT

TMP = os.path.join(os.environ.get('TEMP', '.'), '_liontoolfix')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
REPO = os.path.join(WS, 'repo')
EMPTY = os.path.join(WS, 'emptyrepo')
GARBAGE = os.path.join(WS, 'garbage.bin')
LOG = os.path.join(TMP, 'app.log')

ok_all = True
NEXT = {'name': None, 'args': None}      # 这一轮要假模型发的工具调用
ROUND_RESULT = []                        # 这一轮收到的工具结果


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


def block(name, args):
    """按 Qwen 模板的写法发工具调用：<function=名字> + <parameter=名>值</parameter>"""
    params = ''.join('<parameter=%s>%s</parameter>' % (k, v) for k, v in (args or {}).items())
    return '<tool_call>\n<function=%s>%s</function>\n</tool_call>' % (name, params)


def block_json(name, args):
    """另一种常见写法：参数直接是一个 JSON 对象（解析器现在也要认）"""
    return '<tool_call>\n<function=%s>\n%s\n</function>\n</tool_call>' % (
        name, json.dumps(args, ensure_ascii=False))


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
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = json.loads(self.rfile.read(n) or b'{}')
        msgs = body.get('messages') or []
        joined = ' '.join(str(m.get('content') or '') for m in msgs)

        # 起会话标题那次请求不能当成工具回合
        if '短标题' in joined or '会话标题生成器' in joined:
            content = '标题'
        elif msgs and msgs[-1].get('role') == 'tool':
            ROUND_RESULT.append(str(msgs[-1].get('content') or ''))
            content = '好，这一轮结束了。'
        elif NEXT['name']:
            content = (block_json(NEXT['name'], NEXT['args']) if NEXT.get('json')
                       else block(NEXT['name'], NEXT['args']))
        else:
            content = '没什么要试的了。'
        return self._json({'id': 'c', 'object': 'chat.completion', 'created': int(time.time()),
                           'model': 'mock',
                           'choices': [{'index': 0, 'finish_reason': 'stop',
                                        'message': {'role': 'assistant', 'content': content}}],
                           'usage': {'prompt_tokens': 1, 'completion_tokens': 1,
                                     'total_tokens': 2}})


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


def git(*args, cwd=None):
    env = dict(os.environ)
    env.update({'GIT_TERMINAL_PROMPT': '0',
                'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@t',
                'GIT_COMMITTER_NAME': 't', 'GIT_COMMITTER_EMAIL': 't@t'})
    return subprocess.run(['git'] + list(args), cwd=cwd, env=env, capture_output=True, text=True)


# ---------------------------------------------------------------- 现场
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(REPO, exist_ok=True)
os.makedirs(EMPTY, exist_ok=True)

git('init', '-q', cwd=REPO)
with io.open(os.path.join(REPO, 'a.txt'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('hello\nworld\n')
git('add', '-A', cwd=REPO)
git('commit', '-q', '-m', '初始提交', cwd=REPO)
with io.open(os.path.join(REPO, 'a.txt'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('hello\nworld\n第二行改动\n')

git('init', '-q', cwd=EMPTY)

with io.open(GARBAGE, 'wb') as f:
    f.write(bytes([0x00, 0x01, 0x02, 0xFF, 0xFE, 0x80, 0x81, 0x00, 0x9C, 0x0D, 0x0A]))

with io.open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

print('=' * 72)
print('用户实测坏掉的工具：逐个重跑（每个工具单独一轮）')
print('=' * 72)

log = open(LOG, 'a', encoding='utf-8', errors='replace')
proc = subprocess.Popen([real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
                         '--server.port=%d' % APP_PORT,
                         '--lionbox.runtime.auto-download=false',
                         '--lionbox.runtime.prewarm.enabled=false'],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)


def run_tool(name, args, as_json=False, timeout=600):
    NEXT['name'], NEXT['args'], NEXT['json'] = name, args, as_json
    ROUND_RESULT.clear()
    req(APP + '/api/chat', 'POST',
        {'sessionId': SID, 'message': '试一下 ' + name,
         'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=timeout)
    return ROUND_RESULT[-1] if ROUND_RESULT else ''


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
    SID = (s.get('data') or {}).get('sessionId')

    # 1. git_diff 带 file（用 JSON 写法，顺便验证解析器兜底）
    d = run_tool('git_diff', {'path': REPO, 'file': 'a.txt'}, as_json=True)
    check('★ git_diff 带 file 参数不再报 ambiguous argument',
          'ambiguous' not in d and not d.startswith('工具执行错误'), d.replace('\n', ' ')[:110])
    check('★ JSON 写法的参数也被解析到了（解析器兜底生效）',
          '缺少必需参数' not in d, d.replace('\n', ' ')[:110])
    check('git_diff 真的看到了改动', '第二行改动' in d or '+' in d, d.replace('\n', ' ')[:70])

    # 2. git_remote add 不给 name
    r = run_tool('git_remote', {'path': REPO, 'action': 'add',
                               'url': 'https://github.com/example/demo.git'})
    remotes = git('remote', '-v', cwd=REPO).stdout
    check('★ git_remote add 只给 url（不给 name）也能加上',
          not r.startswith('工具执行错误') and 'origin' in remotes,
          '工具说: %s | 仓库远程: %s' % (r.replace('\n', ' ')[:60], remotes.replace('\n', ' ')[:60]))

    # 3. git_log 空仓库
    lg = run_tool('git_log', {'path': EMPTY})
    check('★ 空仓库的 git_log 给人话（不再把 fatal 当成功）',
          '还没有任何提交' in lg, lg.replace('\n', ' ')[:110])

    # 4. git_commit 没改动
    cm = run_tool('git_commit', {'path': EMPTY, 'message': '第一次提交'})
    check('★ 没有可提交内容时不再报错（模型就不会反复重试）',
          '没有需要提交的改动' in cm or '干净的' in cm, cm.replace('\n', ' ')[:110])

    # 5. modify_file 只给 oldText
    mf = run_tool('modify_file', {'path': os.path.join(REPO, 'a.txt'), 'operation': 'replace',
                                 'oldText': 'hello', 'content': 'HELLO'})
    after = io.open(os.path.join(REPO, 'a.txt'), encoding='utf-8').read()
    check('★ modify_file 只给 oldText + content 就能替换（不用行号）',
          'HELLO' in after, '文件现在是: ' + repr(after[:40]))
    check('modify_file 的结果说明了替换了几处', '替换' in mf, mf.replace('\n', ' ')[:90])

    # 6. word_count 非文本文件
    wc = run_tool('word_count', {'path': GARBAGE})
    check('★ word_count 遇到非文本文件不再直接失败',
          not wc.startswith('工具执行错误') and '字节数' in wc, wc.replace('\n', ' ')[:110])

    # 7. web_search（会开无头浏览器，稍慢）
    print('  （web_search 要开无头浏览器，等它一会儿）')
    wsres = run_tool('web_search', {'query': 'llama.cpp', 'maxResults': 3}, timeout=900)
    check('★ web_search 不再是"需要配置密钥"的占位实现',
          '需要配置搜索' not in wsres, wsres.replace('\n', ' ')[:110])
    check('★ web_search 走的是无头浏览器（或如实说明退到了 HTTP 直取）',
          ('无头浏览器' in wsres) or ('HTTP 直取' in wsres), wsres.replace('\n', ' ')[:130])
    urls = [l.strip() for l in wsres.splitlines() if l.strip().startswith('http')]
    check('★ web_search 真的搜到了结果（至少 1 条带链接）', len(urls) >= 1,
          '拿到 %d 条：%s' % (len(urls), urls[:1]))

    # 8. translate：也要能真翻（免密钥）
    print('  （translate 走公开接口，等它一会儿）')
    tr = run_tool('translate', {'text': 'Hello world, this is a test.', 'to': 'zh'}, timeout=600)
    check('★ translate 不再是"需要配置API密钥"的占位实现',
          '需要配置' not in tr and not tr.startswith('工具执行错误'), tr.replace('\n', ' ')[:130])
    check('★ translate 真给出了译文（中文）',
          any('\u4e00' <= c <= '\u9fff' for c in tr), tr.replace('\n', ' ')[:130])
finally:
    log.close()
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
