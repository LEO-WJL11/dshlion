# -*- coding: utf-8 -*-
r"""新功能后端验证：
A. llama.cpp 参数读写（设置页要用）
B. 模型清单 / 换模型
C. 会话换工作区（对话列表要按工作区分组）
D. 安装时选的模型能生效（读 install-model.txt）
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
MOCK_PORT = 8881
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionfeat')
HOME = os.path.join(TMP, 'home')
WS_A = os.path.join(TMP, 'wsA')
WS_B = os.path.join(TMP, 'wsB')
LOG = os.path.join(TMP, 'app.log')

ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


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
        self.rfile.read(n)
        return self._json({
            'id': 'c', 'object': 'chat.completion', 'created': int(time.time()), 'model': 'mock',
            'choices': [{'index': 0, 'finish_reason': 'stop',
                         'message': {'role': 'assistant', 'content': '好'}}],
            'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}})


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


def req(url, method='GET', body=None, timeout=120):
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


def start_app():
    log = open(LOG, 'a', encoding='utf-8', errors='replace')
    proc = subprocess.Popen([real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
                             '--server.port=%d' % APP_PORT,
                             '--lionbox.runtime.auto-download=false',
                             # 【别依赖真权重】"本地已经有这份模型"是按"文件存在 + 体积 ≥
                             # min-model-bytes（默认 1 GB）"判断的。以前这个套件能过，是因为
                             # 项目根目录真躺着 24 GB 的 gguf；项目清理成"只留代码/md/安装包"
                             # 之后那些文件没了 —— 测试不该依赖那 24 GB，这里把门槛调到 1 MB，
                             # 并在下面自己造一个小文件。
                             '--lionbox.runtime.min-model-bytes=1000000',
                             # 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，开着审核
                             # 文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）
                             '--lionbox.change-review.enabled=false',
                             '--lionbox.runtime.prewarm.enabled=false'],
                            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            return proc
        except Exception:
            time.sleep(1)
    return proc


print('=' * 72)
print('新功能后端：llama 参数 / 模型选择 / 会话换工作区 / 安装选择生效')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS_A, exist_ok=True)
os.makedirs(WS_B, exist_ok=True)

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)
# 模拟安装包写下的"我选了 Q4_K_M"
with open(os.path.join(HOME, '.lioncode', 'install-model.txt'), 'w', encoding='utf-8') as f:
    f.write('lion-merged-Q4_K_M.gguf')

proc = start_app()
try:
    # ---------- A. llama 参数 ----------
    cfg = req(APP + '/api/runtime/local/config').get('data') or {}
    need = ['modelFile', 'ctxSize', 'ngl', 'kvCacheTypeK', 'kvCacheTypeV', 'flashAttn',
            'parallelSlots', 'threads', 'batchSize', 'ubatchSize', 'temperature', 'topP',
            'topK', 'minP', 'repeatPenalty', 'repeatLastN', 'seed', 'maxPredict',
            'extraArgs', 'host', 'port']
    missing = [k for k in need if k not in cfg]
    check('★ 设置页需要的 llama 参数都读得到', not missing, '缺: %s' % missing)
    check('★ 安装时选的是 Q4_K_M（install-model.txt 生效了）',
          cfg.get('modelFile') == 'lion-merged-Q4_K_M.gguf', str(cfg.get('modelFile')))
    check('默认上下文是 262144', cfg.get('ctxSize') == 262144, str(cfg.get('ctxSize')))

    saved = req(APP + '/api/runtime/local/config', 'POST',
                {'ctxSize': 4096, 'threads': 8, 'extraArgs': '--no-mmap --flash-attn on'})
    check('保存参数成功', saved.get('success'), str(saved.get('error'))[:60])
    cfg2 = req(APP + '/api/runtime/local/config').get('data') or {}
    check('★ 改后的值确实生效（ctxSize=4096、threads=8）',
          cfg2.get('ctxSize') == 4096 and cfg2.get('threads') == 8,
          'ctxSize=%s threads=%s' % (cfg2.get('ctxSize'), cfg2.get('threads')))
    check('extraArgs 自由参数也存下来了', '--no-mmap' in str(cfg2.get('extraArgs')))

    # ---------- B. 模型清单 / 换模型 ----------
    models = req(APP + '/api/runtime/local/models').get('data') or []
    official = [m for m in models if not m.get('custom')]
    files = [m.get('file') for m in official]
    check('★ 官方清单有三份（Q8_0 / Q4_K_M / IQ4_XS）', len(official) == 3, str(files))
    check('每行都带 custom/downloading 标记（界面据此决定按钮）',
          all('custom' in m and 'downloading' in m for m in models),
          str([(m.get('file'), m.get('custom'), m.get('downloading')) for m in models[:2]]))
    # 模型目录：界面要把它显示给用户（他得知道往哪塞 GGUF）
    check('★ 接口告诉了模型目录在哪', bool(cfg.get('modelDir')), str(cfg.get('modelDir')))
    check('★ 也给出了会被搜的目录列表',
          isinstance(cfg.get('modelDirs'), list) and len(cfg.get('modelDirs')) >= 1,
          str(cfg.get('modelDirs'))[:120])
    # 清单里必须有一份是“配置里选的”；而“正在用的”
    # 只能是真在跑的那个 —— 没启动过就一份都不该标
    # （以前后端会把配置里选的那份冒充成“正在用”，用户选了 IQ4 却跑 Q8 时看不出来）
    check('清单里标了配置里选的是哪个', sum(1 for m in models if m.get('configured')) == 1,
          str([(m.get('file'), m.get('configured')) for m in models]))
    check('没启动过时不标“正在用”',
          not any(m.get('current') for m in models),
          str([(m.get('file'), m.get('current')) for m in models]))
    check('清单里每份都有体积说明', all(m.get('sizeGb') for m in models))
    check('清单里的体积是对的（8.87 / 5.24 / 4.87）',
          sorted(m['sizeGb'] for m in official) == [4.87, 5.24, 8.87],
          str(sorted(m['sizeGb'] for m in official)))

    sw = req(APP + '/api/runtime/local/model', 'POST',
             {'file': 'lion-merged-Q8_0.gguf', 'download': False})
    check('切模型成功（不触发下载）', sw.get('success'), str(sw.get('error'))[:60])
    cfg3 = req(APP + '/api/runtime/local/config').get('data') or {}
    check('★ 切完 modelFile 变了', cfg3.get('modelFile') == 'lion-merged-Q8_0.gguf',
          str(cfg3.get('modelFile')))
    bad = req(APP + '/api/runtime/local/model', 'POST', {'file': 'nope.gguf'})
    check('给一个不存在的模型会报错并列出可选项', not bad.get('success'),
          str(bad.get('error'))[:80])

    # ---------- B2. 用户自己塞进来的 GGUF ----------
    own = 'my-own-7b-Q4_K_M.gguf'
    own_dir = os.path.join(HOME, '.lioncode', 'models')
    os.makedirs(own_dir, exist_ok=True)
    with open(os.path.join(own_dir, own), 'wb') as f:
        f.write(b'GGUF')
        f.write(b'\0' * (3 * 1024 * 1024))

    models2 = req(APP + '/api/runtime/local/models').get('data') or []
    row = [m for m in models2 if m.get('file') == own]
    check('★ 自己塞进去的 GGUF 会被列出来（custom 标记）',
          bool(row) and row[0].get('custom') is True, str(row[:1])[:140])
    check('★ 自己塞的也算"已下载"、并给出体积（小模型按 MB）',
          bool(row) and row[0].get('downloaded') is True and row[0].get('sizeGb', 0) > 0
          and bool(row[0].get('sizeText')),
          str((row[0].get('sizeGb'), row[0].get('sizeText')) if row else None))

    use = req(APP + '/api/runtime/local/model', 'POST', {'file': own, 'download': False})
    check('★ 能直接切到自己塞的那份',
          use.get('success') and (use.get('data') or {}).get('modelFile') == own,
          str(use.get('error') or (use.get('data') or {}).get('modelFile')))
    cfg_own = req(APP + '/api/runtime/local/config').get('data') or {}
    check('★ 配置里也记下了自己塞的那份',
          cfg_own.get('modelFile') == own, str(cfg_own.get('modelFile')))
    check('改完后它在清单里标成“已选中”',
          any(m.get('file') == own and m.get('configured') for m in
              (req(APP + '/api/runtime/local/models').get('data') or [])), '')

    # 带路径的文件名应该被拒（不能让界面去加载任意路径）
    trav = req(APP + '/api/runtime/local/model', 'POST', {'file': '../../evil.gguf'})
    check('带路径的模型名会被拒绝', not trav.get('success'),
          str(trav.get('error'))[:80])

    # ---------- B3. 下载接口（不碰真网络：自己造一份"已经在本地"的官方权重） ----------
    # 下载接口对"已经有"的判断 = 模型目录里有这个文件且体积 ≥ min-model-bytes。
    # 这里造一个 3 MB 的假 IQ4_XS（启动参数已把门槛降到 1 MB），让它走"已经有了"那条路。
    iq4 = os.path.join(HOME, '.lioncode', 'models', 'lion-merged-IQ4_XS.gguf')
    os.makedirs(os.path.dirname(iq4), exist_ok=True)
    with open(iq4, 'wb') as f:
        f.write(b'GGUF')
        f.write(b'\0' * (3 * 1024 * 1024))
    d1 = req(APP + '/api/runtime/local/models/download', 'POST', {'file': '不存在的模型.gguf'})
    check('★ 下载接口只接受清单里的量化版本（乱填会明确报错）',
          not d1.get('success') and '只能下载' in str(d1.get('error')), str(d1.get('error'))[:80])
    d2 = req(APP + '/api/runtime/local/models/download', 'POST', {'file': 'lion-merged-IQ4_XS.gguf'})
    check('★ 本地已经有的那份会告知"已经有了"，不重复下',
          d2.get('success') and '已经有了' in str(d2.get('message')), str(d2.get('message'))[:80])

    # ---------- C. 会话换工作区 ----------
    wa = req(APP + '/api/workspaces', 'POST', {'path': WS_A}).get('data') or {}
    wb = req(APP + '/api/workspaces', 'POST', {'path': WS_B}).get('data') or {}
    wa_id = wa.get('id') or wa.get('workspaceId')
    wb_id = wb.get('id') or wb.get('workspaceId')
    check('两个工作区都建好了', bool(wa_id) and bool(wb_id), '%s / %s' % (wa_id, wb_id))

    s1 = req(APP + '/api/sessions', 'POST', {'workspaceId': wa_id, 'mode': 'STANDARD'}).get('data') or {}
    sid1 = s1.get('sessionId')
    check('会话建在 A 工作区', sid1 is not None)

    moved = req(APP + '/api/sessions/%s/workspace' % sid1, 'PUT', {'workspaceId': wb_id})
    check('★ 换工作区接口成功', moved.get('success'), str(moved.get('error'))[:60])
    after = (moved.get('data') or {}).get('workspaceId')
    check('★ 会话确实绑到 B 工作区了', after == wb_id, '%s -> %s' % (wa_id, after))

    sessions = req(APP + '/api/sessions').get('data') or []
    check('会话列表里带 workspaceId（界面按它分组）',
          all('workspaceId' in s for s in sessions), str(sessions[:1]))
    check('列表里的 workspaceId 就是新的那个',
          any(s.get('sessionId') == sid1 and s.get('workspaceId') == wb_id for s in sessions))

    bad_move = req(APP + '/api/sessions/%s/workspace' % sid1, 'PUT', {'workspaceId': 'no-such-ws'})
    check('换到不存在的工作区会报错', not bad_move.get('success'), str(bad_move.get('error'))[:60])
    bad_move2 = req(APP + '/api/sessions/no-such-session/workspace', 'PUT', {'workspaceId': wb_id})
    check('给不存在的会话换工作区会报错', not bad_move2.get('success'))
finally:
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    time.sleep(1)
    kill_port(APP_PORT)

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
