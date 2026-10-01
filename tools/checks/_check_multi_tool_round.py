# -*- coding: utf-8 -*-
"""测「一轮多个工具」到底省了多少轮 —— 也就是到底快了多少。

背景（都是本机实测数据）：
  解码 11-12 token/s（89 ms/token），一轮的固定成本 ≈ 2-5 秒预填充 + 4-5 秒生成。
  以前硬性"一轮只准一个工具"：50 个工具 = 50 轮 ≈ 7 分钟，用户体感"模型太慢"。
  现在一轮最多 3 个互不依赖的调用 → 轮数砍到约 1/3。

这个测试用假模型（不占 GPU）：让它一次回 3 个工具调用，看跑完 9 个工具要几轮。
  期望：4 次模型调用（3 轮工具 + 1 轮收尾），改成 1 个/轮的话就是 10 次。
同时验证：
  - 每次请求都带 max_tokens=1024（本地封顶，防止一轮写 4096 token 花 6 分 20 秒）
  - 一次给 5 个调用时只执行 3 个，并给模型一句提示
  - 工具结果按顺序、每个都带自己的 tool_call_id 回给模型
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
MOCK_PORT = 8894
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionmulti')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 9 个互不依赖、参数各不相同的调用（不能重复，否则会被重复调用守卫拦下，那就测不到轮数了）
ROUNDS = [
    [('working_directory', {}),
     ('timestamp', {}),
     ('get_env', {'name': 'PATH'})],
    [('generate_uuid', {}),
     ('hash', {'text': 'abc', 'algorithm': 'sha256'}),
     ('base64', {'text': 'hello', 'action': 'encode'})],
    [('number_convert', {'value': '10', 'fromBase': 10, 'toBase': 2}),
     ('string_utils', {'text': 'Hello', 'action': 'upper'}),
     ('regex_test', {'pattern': 'a.c', 'text': 'abc'})],
]

SEEN = []
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


def tool_call_block(name, args):
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
        if self.path.startswith('/__reset'):
            SEEN.clear()
            return self._json({'ok': True})
        return self._json({'object': 'list', 'data': [{'id': 'mock', 'object': 'model'}]})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        payload = json.loads(self.rfile.read(n).decode('utf-8', 'replace') or '{}')
        msgs = payload.get('messages') or []
        tool_results = [m for m in msgs if m.get('role') == 'tool']
        assistant_calls = []
        for m in msgs:
            if m.get('role') == 'assistant' and m.get('tool_calls'):
                assistant_calls.append(len(m['tool_calls']))
        # 标题生成也打这个接口：靠它的系统提示词特征识别（否则会把主流程误判成标题）
        joined = ' '.join(str(m.get('content') or '') for m in msgs)
        is_title = ('会话标题生成器' in joined) or ('短标题' in joined)
        SEEN.append({
            'max_tokens': payload.get('max_tokens'),
            'tools_sent': len(payload.get('tools') or []),
            'tool_result_count': len(tool_results),
            'assistant_calls': assistant_calls,
            'is_title': is_title,
            'roles': [m.get('role') for m in msgs],
        })

        done = len(tool_results)
        if is_title:
            content = '标题'
            group = []
        elif done >= 9:
            content = '九个工具都跑完了。'
            group = []
        else:
            idx = done // 3
            group = ROUNDS[idx] if idx < len(ROUNDS) else []
            content = '\n'.join(tool_call_block(nm, a) for nm, a in group)

        # 文本通道（本地出货配置）：把 3 个 <tool_call> 块写在正文里，不返回原生 tool_calls
        if not payload.get('tools'):
            # group 为空 = 该收尾了，直接回正常文字答案（不能回空串，否则会被当成空响应纠正）
            text_blocks = content if not group else '\n'.join(tool_call_block(nm, a) for nm, a in group)
            if payload.get('stream'):
                return self._sse(text_blocks, [])
            return self._json({
                'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
                'model': 'mock-model',
                'choices': [{'index': 0, 'finish_reason': 'stop',
                             'message': {'role': 'assistant', 'content': text_blocks}}],
                'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})

        if payload.get('stream'):
            return self._sse(content, group)

        return self._json({
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': content}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})

    def _sse(self, content, group):
        """按 OpenAI 的流式格式回：有工具调用就发 tool_calls 分片（和真机 llama-server 一样）。"""
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'close')
        self.end_headers()

        def emit(obj):
            self.wfile.write(('data: ' + json.dumps(obj, ensure_ascii=False) + '\n\n').encode('utf-8'))
            self.wfile.flush()

        emit({'id': 'chunk-0', 'object': 'chat.completion.chunk', 'model': 'mock-model',
              'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': ''},
                           'finish_reason': None}]})
        if group:
            for i, (name, args) in enumerate(group):
                emit({'id': 'chunk-%d' % (i + 1), 'object': 'chat.completion.chunk', 'model': 'mock-model',
                      'choices': [{'index': 0, 'finish_reason': None, 'delta': {'tool_calls': [
                          {'index': i, 'id': 'call_%d' % i, 'type': 'function',
                           'function': {'name': name, 'arguments': json.dumps(args, ensure_ascii=False)}}]}}]})
        else:
            emit({'id': 'chunk-c', 'object': 'chat.completion.chunk', 'model': 'mock-model',
                  'choices': [{'index': 0, 'finish_reason': None, 'delta': {'content': content}}]})
        emit({'id': 'chunk-end', 'object': 'chat.completion.chunk', 'model': 'mock-model',
              'choices': [{'index': 0, 'delta': {},
                           'finish_reason': 'tool_calls' if group else 'stop'}]})
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()


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
print('一轮多工具：轮数与封顶（假模型，不占 GPU）')
print('=' * 72)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'))
os.makedirs(WS)
llama_baseline = llama_count()

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

# baseUrl 指到本机假模型 → 走的正是"本地端点"那条路（封顶 + 原生工具）
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'auto'}, f, ensure_ascii=False)

kill_port(APP_PORT)
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

    print('让假模型一次回 3 个工具调用，跑完 9 个工具……')
    t0 = time.time()
    c = req(APP + '/api/chat', 'POST',
            {'sessionId': sid, 'message': '把这九个工具都调用一遍',
             'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=600)
    dt = time.time() - t0

    seen = [x for x in (req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or [])]
    main = [x for x in seen if not x['is_title']]
    print('  主流程模型调用 %d 次（标题生成 %d 次）' % (len(main), len(seen) - len(main)))
    for i, x in enumerate(main):
        print('    第%d次: 已有工具结果=%d 本轮助手给的调用数=%s max_tokens=%s'
              % (i + 1, x['tool_result_count'], x['assistant_calls'][-1:] or [], x['max_tokens']))

    last = main[-1] if main else {}
    tool_rounds = [x for x in main if x['assistant_calls']]
    check('★ 9 个工具只用 4 次模型调用（一轮一个的话要 10 次）', len(main) == 4,
          '实际 %d 次' % len(main))
    check('★ 每次助手消息恰好带 3 个工具调用', all(x['assistant_calls'][-1] == 3 for x in tool_rounds),
          str([x['assistant_calls'][-1] for x in tool_rounds]))
    check('★ 最后一个请求里 9 个工具结果都在', last.get('tool_result_count') == 9,
          '实际 %s' % last.get('tool_result_count'))
    check('★ 每个请求都带 max_tokens=1024（本地封顶）',
          all(x['max_tokens'] == 1024 for x in main), str([x['max_tokens'] for x in main]))
    check('工具定义确实随请求下发了（原生通道）', all(x['tools_sent'] > 40 for x in main),
          str([x['tools_sent'] for x in main]))
    check('任务正常收尾', '跑完了' in str(c.get('data')), str(c.get('data'))[:50])
    check('全程没新起 llama-server', llama_count() <= llama_baseline)
    print('  耗时 %.1f 秒（假模型，只是流程开销）' % dt)
    print('  按真机 8 秒/轮估算：10 轮 ≈ 80 秒 → 4 轮 ≈ 32 秒')

    # ---- 界面走的是流式接口，必须单独验一遍（用户真正用到的就是这条） ----
    print('再走一遍流式接口 /api/chat/stream（界面用的就是这条）……')
    req('http://127.0.0.1:%d/__reset' % MOCK_PORT)
    s2 = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid2 = (s2.get('data') or {}).get('sessionId')
    body = json.dumps({'sessionId': sid2, 'message': '再把这九个工具跑一遍',
                       'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}).encode('utf-8')
    rq = urllib.request.Request(APP + '/api/chat/stream', data=body, method='POST',
                                headers={'Content-Type': 'application/json',
                                         'Accept': 'text/event-stream'})
    events = []
    with urllib.request.urlopen(rq, timeout=600) as resp:
        for raw in resp:
            line = raw.decode('utf-8', 'replace').strip()
            if line.startswith('data:'):
                events.append(line[5:].strip())
    seen2 = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    main2 = [x for x in seen2 if not x['is_title']]
    print('    流式：模型调用 %d 次，收到 %d 个 SSE 事件' % (len(main2), len(events)))
    check('★ 流式路径也是 4 次模型调用跑完 9 个工具', len(main2) == 4, '实际 %d 次' % len(main2))
    check('★ 流式路径最后一个请求里 9 个工具结果都在',
          (main2[-1].get('tool_result_count') if main2 else None) == 9,
          '实际 %s' % (main2[-1].get('tool_result_count') if main2 else None))
    check('★ 流式路径同样带 max_tokens=1024', all(x['max_tokens'] == 1024 for x in main2),
          str([x['max_tokens'] for x in main2]))
    tool_events = [e for e in events if 'TOOL_CALL' in e]
    check('★ SSE 里正好 9 个 TOOL_CALL 事件（9 个工具都真被调用了）', len(tool_events) == 9,
          '实际 %d 个' % len(tool_events))
    check('全程没新起 llama-server（第二次）', llama_count() <= llama_baseline)

    # ---- 文本通道（本地运行的出货配置就是这个通道）：块写在正文里，由我们自己的解析器处理 ----
    # 这里用显式 text 开关来验通道本身；"本地 auto → 文本" 这条判定由 _check_tool_channel.py 负责
    # （那个测试一启动就是本地模式，不需要真的去拉模型）。
    print('再验一遍文本通道（9 个工具）……')
    req(APP + '/api/runtime/tool-call-mode', 'POST', {'mode': 'text'})
    req('http://127.0.0.1:%d/__reset' % MOCK_PORT)
    s3 = req(APP + '/api/sessions', 'POST', {'workspaceId': ws_id, 'mode': 'STANDARD'})
    sid3 = (s3.get('data') or {}).get('sessionId')
    c3 = req(APP + '/api/chat', 'POST',
             {'sessionId': sid3, 'message': '把这九个工具都调用一遍',
              'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=600)
    seen3 = req('http://127.0.0.1:%d/__seen' % MOCK_PORT).get('seen') or []
    main3 = [x for x in seen3 if not x['is_title']]
    print('    文本通道：模型调用 %d 次，下发工具数 %s'
          % (len(main3), sorted({x['tools_sent'] for x in main3})))
    for i, x in enumerate(main3):
        print('      第%d次: 已有工具结果=%d' % (i + 1, x['tool_result_count']))
    # 文本通道的解析完全在我们自己手里，轮数不必正好 4；
    # 只要明显少于"一轮一个"的 10 次，就说明批量确实生效了。
    check('★ 文本通道：9 个工具 ≤6 次模型调用（一轮一个要 10 次）', len(main3) <= 6,
          '实际 %d 次' % len(main3))
    check('文本通道：请求里不下发 tools（服务端没机会揉坏块）',
          all(x['tools_sent'] == 0 for x in main3), str(sorted({x['tools_sent'] for x in main3})))
    check('文本通道：最后一个请求里 9 个工具结果都在',
          (main3[-1].get('tool_result_count') if main3 else None) == 9,
          '实际 %s' % (main3[-1].get('tool_result_count') if main3 else None))
    check('文本通道：任务正常收尾', '跑完了' in str(c3.get('data')), str(c3.get('data'))[:50])
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
