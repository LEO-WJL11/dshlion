# -*- coding: utf-8 -*-
r"""审计回归：接口层的空参数 / 越界参数不能再变成 500。

覆盖这几条已修的问题（每条都在前面标注了原来的现象）：
  1) POST /api/workspaces/permission  {}                  → Enum.valueOf(null) 抛 NPE → 500
  2) POST /api/workspaces             {}                  → Path.of(null) 抛 NPE → 500
                                                             （path="" 更糟：等于把进程 CWD 静默注册成工作区）
  3) POST /api/approvals/{toolId}     {}                  → Enum.valueOf(null) 抛 NPE → 500
  4) POST /api/approvals              {"file_write":null} → NPE（catch 只拦 IllegalArgumentException）→ 500
  5) POST /api/chat/adapter/switch    不带 sessionId      → EventStore.recordEvent(null,...) 里
                                                             ConcurrentHashMap 的 null key 抛 NPE → 500
  6) GET  /api/events/..%5C..%5Cevil                      → sessionId 直接当目录名 → 事件 JSON 写到存储目录之外
  7) POST /api/workspaces/permission  小写权限名           → 以前只认大写；现在大小写都认
  8) 同一个工作区再注册一次（前端启动时会走 /default）      → 以前会把用户设的权限等级覆盖回 WORKSPACE_WRITE

判定标准：HTTP 状态必须仍是 200（业务失败用 success=false 表达），且响应体是合法 JSON。
"""
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8953
APP_PORT = 8952
APP = 'http://127.0.0.1:%d' % APP_PORT
# 【为什么数据目录放在 target 下而不是 %TEMP%】
#   1) application.yml 里的 ${user.home} 是**构建时**被 maven 资源过滤写死的
#      （见审计报告 D-1），所以 -Duser.home 对这些路径无效，app 会去写真实用户目录；
#   2) 本机沙箱只允许写会话工作区（仓库目录）内，写 %TEMP% 或用户目录会被拒。
#   两条加起来，只有把下面这几个目录显式指到 target 下，套件才是隔离且能跑通的。
TMP = os.path.join(ROOT, 'target', '_lionapi')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
EVENTS_DIR = os.path.join(TMP, 'data', 'events')
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
            'id': 'chatcmpl-mock', 'object': 'chat.completion', 'created': int(time.time()),
            'model': 'mock-model',
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


def call(url, method='GET', body=None, timeout=60):
    """返回 (http_status, 解析后的 json 或 None)。**不抛异常**，500 也要能看到。"""
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read().decode('utf-8', 'replace')
            return resp.status, (json.loads(raw) if raw.strip().startswith('{') else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, None


def expect_no_500(label, status, payload):
    """只要求"不是 5xx、并且回了结构化 JSON" —— 业务上失败（success=false）是正确的。"""
    check(label + '：不返回 5xx', status < 500, 'HTTP %d' % status)
    check(label + '：响应体是结构化 JSON', isinstance(payload, dict) and 'success' in payload,
          str(payload)[:80] if payload else '(空)')


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
print('审计回归：空参数/越界参数不再 500 + 工作区权限不被重置')
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
                         # 显式覆盖三个数据目录：既是为了隔离，也是为了绕开"${user.home} 被构建时写死"
                         '--lion.workspace.default-path=' + os.path.join(TMP, 'data'),
                         '--lion.event.store-path=' + EVENTS_DIR,
                         '--lion.plugin.scan-path=' + os.path.join(TMP, 'data', 'plugins'),
                         '--lionbox.runtime.auto-download=false',
                         '--lionbox.runtime.prewarm.enabled=false'],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
try:
    for _ in range(90):
        try:
            call(APP + '/api/runtime/mode', timeout=3)
            break
        except Exception:
            time.sleep(1)

    # 1) 权限接口：空 body / 缺字段 / 非法枚举
    st, body = call(APP + '/api/workspaces/permission', 'POST', {})
    expect_no_500('1 权限接口传 {}', st, body)
    st, body = call(APP + '/api/workspaces/permission', 'POST', {'id': 'x'})
    expect_no_500('1b 权限接口缺 permission', st, body)
    st, body = call(APP + '/api/workspaces/permission', 'POST', {'id': 'x', 'permission': 'NOPE'})
    expect_no_500('1c 权限接口非法枚举', st, body)
    check('1c 非法枚举被业务性拒绝', body is not None and body.get('success') is False, str(body)[:60])

    # 2) 注册工作区：空 body / 空路径 / 非法路径
    st, body = call(APP + '/api/workspaces', 'POST', {})
    expect_no_500('2 注册工作区传 {}', st, body)
    check('2 空路径被拒（不再静默注册进程 CWD）',
          body is not None and body.get('success') is False, str(body)[:80])
    st, body = call(APP + '/api/workspaces', 'POST', {'path': '   '})
    expect_no_500('2b 注册工作区传空白路径', st, body)
    st, body = call(APP + '/api/workspaces', 'POST', {'path': 'C:\\bad<>|path'})
    expect_no_500('2c 注册工作区传非法字符路径', st, body)

    # 3/4) 审批策略：空策略 / 空值 / 空 body
    st, body = call(APP + '/api/approvals/file_write', 'POST', {})
    expect_no_500('3 单个审批策略传 {}', st, body)
    st, body = call(APP + '/api/approvals', 'POST', {'file_write': None})
    expect_no_500('4 批量审批策略带 null 值', st, body)
    st, body = call(APP + '/api/approvals', 'POST', {})
    expect_no_500('4b 批量审批策略传 {}', st, body)

    # 5) 切适配器不带 sessionId（原来在 EventStore 里 null key 抛 NPE）
    st, body = call(APP + '/api/chat/adapter/switch', 'POST',
                    {'adapterType': 'OPENAI_COMPATIBLE'})
    expect_no_500('5 切适配器不带 sessionId', st, body)

    # 6) 事件写入路径：sessionId 里塞路径穿越，不能把事件 JSON 写到事件目录外面
    #    （走 /api/chat/adapter/switch：它不校验会话是否存在，会直接 recordEvent(sessionId,...)，
    #      这正是"sessionId 被当目录名用"的真实入口）
    st, body = call(APP + '/api/chat/adapter/switch', 'POST',
                    {'adapterType': 'OPENAI_COMPATIBLE', 'sessionId': r'..\..\..\evil_session'})
    expect_no_500('6 用穿越 sessionId 记事件', st, body)
    st2, body2 = call(APP + '/api/events/ok-session-1', timeout=30)
    expect_no_500('6b 事件接口正常 sessionId', st2, body2)
    check('6b 正常查询返回空事件列表', body2 is not None and body2.get('data') == [], str(body2)[:60])

    outside = os.path.join(TMP, 'data', 'evil_session')
    check('6c 没有在事件目录之外建出目录', not os.path.exists(outside), outside)
    # 事件本身要照常落盘（只是目录名被清洗成安全形式）
    sanitized = []
    for dirpath, dirnames, _ in os.walk(EVENTS_DIR):
        sanitized += [d for d in dirnames if 'evil_session' in d]
    check('6d 事件仍然被安全地存下来了（目录名已清洗）', bool(sanitized), str(sanitized))
    check('6e 清洗后的目录名不含路径分隔符',
          all('/' not in d and '\\' not in d for d in sanitized), str(sanitized))

    # 7/8) 权限大小写 + 重复注册不覆盖权限
    st, body = call(APP + '/api/workspaces', 'POST', {'path': WS})
    ws_id = ((body or {}).get('data') or {}).get('workspaceId') or ((body or {}).get('data') or {}).get('id')
    check('7 注册工作区成功', bool(ws_id), str(ws_id))
    st, body = call(APP + '/api/workspaces/permission', 'POST',
                    {'id': ws_id, 'permission': 'read_only'})
    check('7b 小写权限名也认（read_only）',
          body is not None and body.get('success') is True, str(body)[:80])
    # 再注册一次同一个路径：权限必须还是 READ_ONLY
    call(APP + '/api/workspaces', 'POST', {'path': WS})
    st, body = call(APP + '/api/workspaces', 'GET')
    perms = {w.get('id'): w.get('permission') for w in ((body or {}).get('data') or [])}
    check('8 重复注册不会把 READ_ONLY 重置回 WORKSPACE_WRITE',
          perms.get(ws_id) == 'READ_ONLY', str(perms.get(ws_id)))

    # 9) 音效配置：非法音量/字符串不再 500，并按默认值兜底
    st, body = call(APP + '/api/notification', 'POST',
                    {'volume': 'abc', 'enabled': 'yes', 'minIntervalMs': -5})
    expect_no_500('9 音效配置传非法值', st, body)
    data = (body or {}).get('data') or {}
    check('9b volume 回落成数字且在 0~100',
          isinstance(data.get('volume'), int) and 0 <= data['volume'] <= 100, str(data.get('volume')))
    check('9c minIntervalMs 被夹到 >= 0',
          isinstance(data.get('minIntervalMs'), int) and data['minIntervalMs'] >= 0,
          str(data.get('minIntervalMs')))

    # 10) 事件日志目录确实存在（说明事件存储初始化正常，没被上面的穿越请求搞坏）
    check('10 事件存储目录正常', os.path.isdir(EVENTS_DIR), EVENTS_DIR)
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
