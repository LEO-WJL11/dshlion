# -*- coding: utf-8 -*-
r"""复现并验证「选了 IQ4，启动还是 Q8」这个 bug。

用户真机上的情况：
  install-model.txt  = lion-merged-IQ4_XS.gguf      （安装时选的，写对了）
  app-config.json    = llama.modelFile: IQ4_XS      （应用也应用对了）
  磁盘上只有 lion-merged-Q8_0.gguf                   （IQ4 从没被下载）
  → 启动时 resolvesModel() "没找到配置的就拿现成的"，于是跑的是 Q8，且界面上不说。

修好之后应当是：**配置的那个优先 → 缺了就下载它 → 实在下不来才兜底，并且明说**。
这里用两个小假 gguf（把 min-model-bytes 调小）来验证前两步的判定，不真下 5 GB。
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8879
APP_PORT = 8899
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionmodel')
HOME = os.path.join(TMP, 'home')
APPDIR = os.path.join(TMP, 'appdir')          # 冒充安装目录（放假 gguf）
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
        return self._json({'id': 'c', 'object': 'chat.completion', 'created': int(time.time()),
                           'model': 'mock',
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


def fake_gguf(path, mb=8):
    """造一个体积够大、带 GGUF 魔数的假权重（只为骗过 min-model-bytes 与魔数校验）。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(b'GGUF')
        f.write(b'\0' * (mb * 1024 * 1024))


def start_app(extra=None):
    # 【坑】-D 必须放在 -jar **前面**：放后面就只是程序参数，Spring 不认，
    # 于是 lionbox.home 不生效、appDirs() 照样去扫仓库根目录（那里真有那三份权重），
    # 测试就完全不隔离。之前那次"跑的还是 IQ4"的假象一半来自这个。
    args = [real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME,
            '-Dlionbox.home=' + APPDIR, '-jar', JAR,
            '--server.port=%d' % APP_PORT,
            '--lionbox.runtime.auto-download=false',
            '--lionbox.runtime.prewarm.enabled=false',
            '--lionbox.runtime.min-model-bytes=1000000',
            # 【坑】运行时端口必须换开：默认 8788 上跑着用户自己那台 LionBox 的
            # llama-server，healthy() 一探测就返回 true，ensureRunning() 直接返回，
            # 永远走不到"下载配置的那份"分支 —— 测试会假失败。
            '--lionbox.runtime.port=8790']
    # 额外参数里同名的 --key=value 要**盖掉**默认的，
    # 不能直接追加：Spring 会把两个值拼成 "false,true" 然后报错。
    if extra:
        keys = set()
        for a in extra:
            if a.startswith('--') and '=' in a:
                keys.add(a.split('=', 1)[0])
        args = [a for a in args if not (a.startswith('--') and a.split('=', 1)[0] in keys)]
        args += extra
    log = open(LOG, 'a', encoding='utf-8', errors='replace')
    # cwd 也放临时目录：appDirs() 会扫 user.dir，仓库根目录下真有那三份权重，不隔离就会误判
    proc = subprocess.Popen(args, cwd=TMP, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            return proc, log
        except Exception:
            time.sleep(1)
    return proc, log


print('=' * 72)
print('选了 IQ4 却启动 Q8 —— 复现与验证')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(APPDIR, exist_ok=True)
# 磁盘上只有 Q8（和用户机器一样）；配置的是 IQ4
fake_gguf(os.path.join(APPDIR, 'lion-merged-Q8_0.gguf'))
with open(os.path.join(HOME, '.lioncode', 'install-model.txt'), 'w', encoding='utf-8') as f:
    f.write('lion-merged-IQ4_XS.gguf')

srv = ThreadingHTTPServer(('127.0.0.1', MOCK_PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

proc, log = start_app()
try:
    cfg = req(APP + '/api/runtime/local/config').get('data') or {}
    check('配置里确实是我们选的 IQ4_XS（安装时选的模型生效了）',
          cfg.get('modelFile') == 'lion-merged-IQ4_XS.gguf', str(cfg.get('modelFile')))
    check('★ IQ4_XS 会被认成"没下载"（磁盘上没有它）',
          cfg.get('configuredDownloaded') is False, str(cfg.get('configuredDownloaded')))
    check('★ 还没启动过时不谎报"正在用"（空着，不是报成配置的那个）',
          not (cfg.get('modelInUse') or ''), repr(cfg.get('modelInUse')))
    check('★ 日志里明说了为什么不是你选的那个',
          'IQ4_XS' in str(cfg.get('modelMismatch') or ''),
          str(cfg.get('modelMismatch'))[:120])

    # 还没启动过：模型清单里"正在用"必须一个都不标（以前会把配置的 IQ4 假报成正在用）
    m0 = req(APP + '/api/runtime/local/models').get('data') or []
    check('★ 一个都没启动时，清单里不标任何"正在用"',
          not [m['file'] for m in m0 if m.get('current')],
          str([m['file'] for m in m0 if m.get('current')]))

    # 试着启动一次：没有 llama-server.exe，所以会失败，但模型判定发生在更早
    try:
        req(APP + '/api/runtime/local/start', 'POST', timeout=60)
    except Exception:
        pass
    cfg2 = req(APP + '/api/runtime/local/config').get('data') or {}
    in_use = str(cfg2.get('modelInUse') or '')
    st = cfg2.get('status') or {}
    print('  解析到的模型路径 = %s' % st.get('resolvedModelPath'))
    import glob as _g
    print('  临时目录下的 gguf: %s' % [os.path.basename(x) for x in _g.glob(os.path.join(APPDIR, '*.gguf'))])
    print('  配置的模型 = %s' % cfg2.get('modelFile'))
    print('  实际在用的 = %s' % in_use)
    print('  不一致提示 = %s' % (cfg2.get('modelMismatch') or '(无)'))
    check('★ 实际在用的是兜底的那个（Q8_0），不是配置的 IQ4_XS',
          in_use == 'lion-merged-Q8_0.gguf', in_use)
    check('★ 界面能拿到"为什么不是你要的那个"的说明',
          'IQ4_XS' in str(cfg2.get('modelMismatch') or '')
          and 'Q8_0' in str(cfg2.get('modelMismatch') or ''),
          str(cfg2.get('modelMismatch'))[:120])

    models = req(APP + '/api/runtime/local/models').get('data') or []
    cur = [m['file'] for m in models if m.get('current')]
    conf = [m['file'] for m in models if m.get('configured')]
    check('★ 模型清单里"正在用"标的是 Q8_0', cur == ['lion-merged-Q8_0.gguf'], str(cur))
    check('★ "已选中"标的是 IQ4_XS（和正在用的分开标）',
          conf == ['lion-merged-IQ4_XS.gguf'], str(conf))

    # 把 IQ4 放进目录（模拟"下载完成"）→ 重启后应当就用配置的那个
    fake_gguf(os.path.join(APPDIR, 'lion-merged-IQ4_XS.gguf'))
    req(APP + '/api/runtime/local/config', 'POST', {'ngl': 0})   # 触发一次配置读取
    try:
        req(APP + '/api/runtime/local/start', 'POST', timeout=60)
    except Exception:
        pass
    cfg3 = req(APP + '/api/runtime/local/config').get('data') or {}
    print('  IQ4 补齐后实际在用 = %s' % cfg3.get('modelInUse'))
    check('★ 权重补齐后，实际在用的就是配置的 IQ4_XS（不再兜底）',
          cfg3.get('modelInUse') == 'lion-merged-IQ4_XS.gguf', str(cfg3.get('modelInUse')))
    check('一致之后不再显示不一致提示', not (cfg3.get('modelMismatch') or ''),
          str(cfg3.get('modelMismatch'))[:80])
    check('★ 权重补齐后"已下载"标记也要跟着变',
          cfg3.get('configuredDownloaded') is True, str(cfg3.get('configuredDownloaded')))
finally:
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    srv.shutdown()
    srv.server_close()
    time.sleep(1)
    kill_port(APP_PORT)
    log.close()

# ------------------------------------------------------------------
# 第二阶段：exe 在、自动下载开着的时候，必须去下**用户选的那一个**，
# 而不是又拿现成的 Q8 顶上。用户碰到的就是这个：选了 IQ4 却跑的 Q8。
# 这里把下载地址指向本地假服务器，不真下 4.9 GB。
# ------------------------------------------------------------------
DL_PORT = 8878
DL_HITS = []


class DlHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def do_GET(self):
        path = urllib.parse.unquote(self.path)
        DL_HITS.append(path)
        if 'FilePath=lion-merged-IQ4_XS.gguf' not in path:
            body = b'not found'
            self.send_response(404)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        size = 8 * 1024 * 1024
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(size))
        self.end_headers()
        # 分块 + 小睡：8 MB 瞬间下完的话，测试就抓不到"下载中"这个中间态了
        chunk = 1024 * 1024
        sent = 0
        while sent < size:
            n = min(chunk, size - sent)
            block = (b'GGUF' + b'\0' * (n - 4)) if sent == 0 else b'\0' * n
            self.wfile.write(block)
            self.wfile.flush()
            sent += n
            time.sleep(0.2)


print('-' * 72)
print('第二阶段：自动下载开着时，会不会去下你选的那一个')
# 假下载服务器：二、三阶段共用一个（别同端口反复绑，见文件头那个坑）
dlsrv = ThreadingHTTPServer(('127.0.0.1', DL_PORT), DlHandler)
threading.Thread(target=dlsrv.serve_forever, daemon=True).start()
os.remove(os.path.join(APPDIR, 'lion-merged-IQ4_XS.gguf'))
os.makedirs(os.path.join(APPDIR, 'runtime-vulkan'), exist_ok=True)
# 假的运行时：只为让"exe 存在"成立；真去跑会立刻失败，不会真启动 llama-server
with open(os.path.join(APPDIR, 'runtime-vulkan', 'llama-server.exe'), 'wb') as f:
    f.write(b'not a real exe')

proc2, log2 = start_app(extra=[
    '--lionbox.runtime.auto-download=true',
    '--lionbox.runtime.model-base-url=http://127.0.0.1:%d' % DL_PORT])
try:
    # 下载**期间**的状态：前台进度条就是吃这几个字段的
    seen = []

    def poll_status():
        for _ in range(60):
            try:
                st = json.loads(urllib.request.urlopen(APP + '/api/runtime/local',
                                                       timeout=5).read().decode('utf-8')).get('data') or {}
                if st.get('phase') == 'downloading':
                    seen.append((st.get('downloadingFile'), st.get('downloadBytes'),
                                 st.get('downloadTotal')))
                    if len(seen) >= 3:
                        return
            except Exception:
                pass
            time.sleep(0.2)

    th = threading.Thread(target=poll_status, daemon=True)
    th.start()
    try:
        req(APP + '/api/runtime/local/start', 'POST', timeout=180)
    except Exception:
        pass
    th.join(timeout=30)

    check('★ 下载期间状态是"下载中"（前台进度条靠它出现）',
          bool(seen), '采样 %d 次' % len(seen))
    check('★ 状态里写着正在下哪一份（进度条上要显示文件名）',
          bool(seen) and seen[0][0] == 'lion-merged-IQ4_XS.gguf',
          str(seen[:1]))
    check('★ 已下字节在涨（进度条会动）',
          len(seen) >= 2 and seen[-1][1] > seen[0][1],
          '%s → %s' % (seen[0][1] if seen else None, seen[-1][1] if seen else None))
    check('★ 总量也报出来了（能算百分比和剩余时间）',
          bool(seen) and seen[-1][2] == 8 * 1024 * 1024, str(seen[-1][2] if seen else None))

    got = any('IQ4_XS' in h for h in DL_HITS)
    check(u'\u2605 自动下载去下的是**用户选的那个** IQ4_XS（不是 Q8）',
          got, str(DL_HITS[:2]))
    check(u'\u2605 下完落盘在安装目录里',
          os.path.isfile(os.path.join(APPDIR, 'lion-merged-IQ4_XS.gguf')))
    cfg4 = req(APP + '/api/runtime/local/config').get('data') or {}
    check(u'\u2605 下完就在用 IQ4_XS 了',
          cfg4.get('modelInUse') == 'lion-merged-IQ4_XS.gguf', str(cfg4.get('modelInUse')))
    check(u'\u2605 下完不再提示"还没下载"',
          not (cfg4.get('modelMismatch') or ''), str(cfg4.get('modelMismatch'))[:80])
finally:
    if proc2.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc2.pid)], capture_output=True)
    time.sleep(1)
    kill_port(APP_PORT)
    log2.close()

# ------------------------------------------------------------------
# 第三阶段：开机就在后台把你选的那份下好。
# 为什么要这个：否则 4.87 GB 是在**第一条消息**那一刻开始下的，
# 那条消息就得干等好几分钟，用户只会觉得卡死了。
# ------------------------------------------------------------------
print('-' * 72)
print('第三阶段：开机后台自动补齐你选的那份')
os.remove(os.path.join(APPDIR, 'lion-merged-IQ4_XS.gguf'))
DL_HITS[:] = []
# 切成"本地模型"模式：开机后台下载只在这个模式下跑
# （用户在用自定义 API 时，不该被偷偷占掉 5 GB 硬盘）
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'local', 'provider': 'lionbox-local',
               'baseUrl': 'http://127.0.0.1:8788/v1', 'apiKey': '',
               'model': 'lion-models1', 'toolCallMode': 'text'}, f, ensure_ascii=False)

proc3, log3 = start_app(extra=[
    '--lionbox.runtime.auto-download=true',
    '--lionbox.runtime.model-base-url=http://127.0.0.1:%d' % DL_PORT])
try:
    # 只等，不调用任何下载/启动接口
    landed = False
    for _ in range(60):
        if os.path.isfile(os.path.join(APPDIR, 'lion-merged-IQ4_XS.gguf')):
            landed = True
            break
        time.sleep(1)
    check(u'\u2605 开机后台就把用户选的 IQ4_XS 下好了（不用等第一条消息）'
          , landed, str(DL_HITS[:1]))
    check(u'\u2605 后台下完也没偷偷加载模型（不占显存）',
          not (req(APP + '/api/runtime/local/config').get('data') or {}).get('modelInUse'))
    mm = req(APP + '/api/runtime/local/models').get('data') or []
    iq4 = [m for m in mm if m['file'] == 'lion-merged-IQ4_XS.gguf']
    check(u'\u2605 清单里 IQ4_XS 标成了“已下载”',
          bool(iq4) and iq4[0].get('downloaded') is True,
          str(iq4[0] if iq4 else None))
finally:
    if proc3.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc3.pid)], capture_output=True)
    dlsrv.shutdown()
    dlsrv.server_close()          # 关键：shutdown 不关套接字，得再 server_close
    time.sleep(1)
    kill_port(APP_PORT)
    log3.close()

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
