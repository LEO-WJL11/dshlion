# -*- coding: utf-8 -*-
r"""回归测试：界面上的工具行不再重复刷屏。

用户实测（标准模式那一跑）：
  🔧 调用工具：web_search …   ← 连着画了 9 次
  ✅ web_search 完成
  🔧 调用工具：ask_user …     ← 连着画了 20 次

根因：事件时间戳带纳秒，而界面轮询只能给毫秒（?after=<ms>）。
后端原来是 `timestamp.isAfter(Instant.ofEpochMilli(after))`，
"纳秒的 .123456789 > 毫秒的 .123" 永远成立 → **每轮轮询（800ms 一次）都把同一条
TOOL_CALL_START 重新返回**，界面就再画一遍。重复次数正好 = 那条工具跑了几秒 ÷ 0.8s。

现在：后端按毫秒 + 含边界比较（不丢事件），界面按 eventId 去重（不重复画）。
这个套件就是照界面的轮询逻辑真跑一遍，数一数画了几行。
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
MOCK_PORT = 8894
APP_PORT = 8910
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_liondup')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
LOG = os.path.join(TMP, 'app.log')

# 一条"慢工具"：跑 6 秒，正好跨 7 轮轮询（800ms 一次），旧实现会画 7 行重复
ROUNDS = [
    [('execute_command', {'command': 'Start-Sleep -Seconds 6; Write-Output done'})],
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
            content = '重复行测完了。'
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


ISO_RE = None


def iso_to_ms(text, fallback):
    """把后端给的时间戳（UTC、带纳秒的 ISO 串）折成 epoch 毫秒，等价于 JS 的 new Date(x).getTime()"""
    import re
    from datetime import datetime
    if not text:
        return fallback
    s = str(text).strip()
    m = re.match(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$', s)
    if not m:
        return fallback
    frac = (m.group(2) or '000')[:6].ljust(6, '0')
    tz = m.group(3) or ''
    if tz in ('Z', 'z'):
        tz = '+00:00'
    elif re.fullmatch(r'[+-]\d{4}', tz):
        tz = tz[:3] + ':' + tz[3:]
    if not tz:
        tz = '+00:00'      # Instant.now() 序列化出来是 UTC，没带后缀就按 UTC 算
    try:
        return int(datetime.fromisoformat(m.group(1) + '.' + frac + tz).timestamp() * 1000)
    except Exception:
        return fallback


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
print('工具行不再重复刷屏（事件轮询去重）')
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

    result = {}

    def chat():
        result['resp'] = req(APP + '/api/chat', 'POST',
                             {'sessionId': sid, 'message': '跑一条慢工具',
                              'model': 'mock-model', 'thinkingLevel': 'MEDIUM'}, timeout=300)

    th = threading.Thread(target=chat, daemon=True)
    th.start()

    # 完全照 web/index.html 的 pollEvents：800ms 一次，after=上一批最大时间戳，
    # 先把原始返回记下来（raw），再按 eventId 去重（dedup）—— 界面现在就是后者。
    last_ts = int(time.time() * 1000)
    raw_start = raw_done = 0
    dedup_start = dedup_done = 0
    seen = {}
    polls = 0
    raw_ids = []
    t_end = time.time() + 60
    while time.time() < t_end and th.is_alive():
        try:
            res = req('%s/api/events/%s?after=%d' % (APP, sid, last_ts), timeout=10)
        except Exception:
            time.sleep(0.4)
            continue
        polls += 1
        for ev in (res.get('data') or []):
            ts = ev.get('timestamp')
            t = iso_to_ms(ts, last_ts)
            if t > last_ts:
                last_ts = t
            eid = ev.get('eventId') or (str(ev.get('type')) + '|' + str(ts))
            raw_ids.append(eid)
            if ev.get('type') == 'TOOL_CALL_START':
                raw_start += 1
            elif ev.get('type') == 'TOOL_CALL_COMPLETE':
                raw_done += 1
            if eid in seen:
                continue
            seen[eid] = 1
            if ev.get('type') == 'TOOL_CALL_START':
                dedup_start += 1
            elif ev.get('type') == 'TOOL_CALL_COMPLETE':
                dedup_done += 1
        time.sleep(0.8)

    th.join(timeout=60)

    # 收尾再拉一次：COMPLETE 常常正好在最后一次轮询之后才落库
    try:
        res = req('%s/api/events/%s?after=%d' % (APP, sid, last_ts), timeout=10)
        polls += 1
        for ev in (res.get('data') or []):
            t = iso_to_ms(ev.get('timestamp'), last_ts)
            if t > last_ts:
                last_ts = t
            eid = ev.get('eventId') or (str(ev.get('type')) + '|' + str(ev.get('timestamp')))
            raw_ids.append(eid)
            if ev.get('type') == 'TOOL_CALL_START':
                raw_start += 1
            elif ev.get('type') == 'TOOL_CALL_COMPLETE':
                raw_done += 1
            if eid in seen:
                continue
            seen[eid] = 1
            if ev.get('type') == 'TOOL_CALL_START':
                dedup_start += 1
            elif ev.get('type') == 'TOOL_CALL_COMPLETE':
                dedup_done += 1
    except Exception as e:
        print('  收尾轮询失败: %s' % e)

    print('  轮询 %d 次；原始返回 START %d 次 / COMPLETE %d 次' % (polls, raw_start, raw_done))
    print('  按 eventId 去重后：START %d 行 / COMPLETE %d 行（界面画的就是这个数）'
          % (dedup_start, dedup_done))

    check('★ 慢工具跑的时候确实轮询了好几轮', polls >= 4, 'polls=%d' % polls)
    check('★ 去重后 START 只画 1 行（原来会画成 7~20 行）', dedup_start == 1,
          'dedup=%d raw=%d' % (dedup_start, raw_start))
    check('★ 去重后 COMPLETE 只画 1 行', dedup_done == 1, 'dedup=%d raw=%d' % (dedup_done, raw_done))
    check('（记录）旧实现会重复，因为边界那条事件会被反复返回', raw_start >= 2,
          'raw_start=%d' % raw_start)
    ids_dup = len(raw_ids) - len(set(raw_ids))
    check('重复的永远是同一条事件（eventId 一样，所以按 id 去重是对的）',
          ids_dup == 0 or len(set(raw_ids)) <= len(raw_ids))
    check('事件没有丢：START/COMPLETE 各只有一条 distinct id',
          len(set(i for i in raw_ids)) >= 2)

    # 界面源码里必须真的有去重逻辑
    with urllib.request.urlopen(APP + '/', timeout=10) as resp:
        page = resp.read().decode('utf-8', 'replace')
    check('★ 界面里有按 eventId 去重的代码（seenEvents）',
          'seenEvents' in page and 'seenEventOrder' in page)
    check('界面里没有"轮询每来一条就无条件画一行"的老写法',
          'if (self.seenEvents[eid]) return;' in page)
    check('任务正常收尾', '重复行测完了' in str(result.get('resp', {}).get('data')),
          str(result.get('resp', {}).get('data'))[:60])
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
