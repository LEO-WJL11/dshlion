# -*- coding: utf-8 -*-
r"""模式只留标准/极简 —— 单独验一遍。

要证明三件事：
  ① 只有标准/极简能选，别的名字（PTC / 创造）会被归一成标准，乱填的名字报错不 500；
  ② **老会话不会坏**：磁盘上存着 mode=PTC / CREATIVE 的会话，重启后照样加载出来，
     而且模式显示成标准（枚举常量故意没删，就是为了这个）；
  ③ 切换模式、提示词预览这类入口也过归一，不会从后门把 PTC 放回来。
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

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
MOCK_PORT = 8883
APP_PORT = 8898
APP = 'http://127.0.0.1:%d' % APP_PORT
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionmodes')
HOME = os.path.join(TMP, 'home')
WS = os.path.join(TMP, 'ws')
WSROOT = os.path.join(TMP, 'wsroot')
LOG = os.path.join(TMP, 'app.log')
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


class Handler:
    pass


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


def req(url, method='GET', body=None, timeout=60):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8', 'replace'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return json.loads(raw)
        except Exception:
            return {'success': False, 'error': 'HTTP %d: %s' % (e.code, raw[:120])}


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
                             '--lion.workspace.default-path=' + WSROOT,
                             '--lionbox.runtime.auto-download=false',
                             '--lionbox.runtime.prewarm.enabled=false'],
                            cwd=TMP, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            req(APP + '/api/runtime/mode', timeout=3)
            return proc, log
        except Exception:
            time.sleep(1)
    return proc, log


print('=' * 72)
print('模式只留标准和极简')
print('=' * 72)
kill_port(APP_PORT)
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.join(HOME, '.lioncode'), exist_ok=True)
os.makedirs(WS, exist_ok=True)
sessions_dir = os.path.join(WSROOT, '.lioncode', 'sessions')
os.makedirs(sessions_dir, exist_ok=True)

# 老的 app-config（自定义 API，不需要真模型）
with open(os.path.join(HOME, '.lioncode', 'app-config.json'), 'w', encoding='utf-8') as f:
    json.dump({'providerMode': 'custom', 'provider': 'lionbox-custom',
               'baseUrl': 'http://127.0.0.1:%d/v1' % MOCK_PORT, 'apiKey': 'sk-mock',
               'model': 'mock-model', 'toolCallMode': 'text'}, f, ensure_ascii=False)

# 【关键】假装是"上一版留下来的"会话文件：mode 写的是 PTC / CREATIVE
def seed(sid, mode, name):
    with open(os.path.join(sessions_dir, sid + '.json'), 'w', encoding='utf-8') as f:
        json.dump({'sessionId': sid, 'workspaceId': WS, 'mode': mode,
                   'createdAt': '2026-09-01T10:00:00Z', 'name': name}, f, ensure_ascii=False)


seed('old-ptc-session', 'PTC', '老的预规划会话')
seed('old-creative-session', 'CREATIVE', '老的创造会话')
seed('old-minimal-session', 'MINIMAL', '老的极简会话')

proc, log = start_app()
try:
    # ① 老会话能加载、模式被归一
    lst = req(APP + '/api/sessions').get('data') or []
    by_id = {s['sessionId']: s for s in lst}
    check('★ 老会话（mode=PTC/CREATIVE）照样加载得出来，没被当成坏文件',
          'old-ptc-session' in by_id and 'old-creative-session' in by_id,
          str(sorted(by_id.keys()))[:120])
    check('★ 老的 PTC 会话显示成标准模式',
          (by_id.get('old-ptc-session') or {}).get('mode') == 'STANDARD',
          str((by_id.get('old-ptc-session') or {}).get('mode')))
    check('★ 老的 CREATIVE 会话也显示成标准模式',
          (by_id.get('old-creative-session') or {}).get('mode') == 'STANDARD',
          str((by_id.get('old-creative-session') or {}).get('mode')))
    check('老的极简会话还是极简',
          (by_id.get('old-minimal-session') or {}).get('mode') == 'MINIMAL',
          str((by_id.get('old-minimal-session') or {}).get('mode')))

    # ② 新建会话：只有这两种，别的都归一到标准
    def new_session(mode):
        r = req(APP + '/api/sessions', 'POST', {'workspaceId': WS, 'mode': mode})
        return r, ((r.get('data') or {}).get('mode'))

    r, m = new_session('MINIMAL')
    check('新建会话能选极简', r.get('success') and m == 'MINIMAL', str(m))
    r, m = new_session('STANDARD')
    check('新建会话能选标准', r.get('success') and m == 'STANDARD', str(m))
    r, m = new_session('PTC')
    check('★ 还传 PTC 的话会被归一成标准（老前端不会坏）', r.get('success') and m == 'STANDARD', str(m))
    r, m = new_session('CREATIVE')
    check('★ 还传 CREATIVE 的话也会被归一成标准', r.get('success') and m == 'STANDARD', str(m))
    r, m = new_session('')
    check('模式留空 → 标准', r.get('success') and m == 'STANDARD', str(m))
    r = req(APP + '/api/sessions', 'POST', {'workspaceId': WS, 'mode': 'NOPE'})
    check('★ 乱填模式名会明确报错（不是 500）',
          (not r.get('success')) and '无效的工作模式' in str(r.get('error')), str(r.get('error'))[:90])

    # ③ 切换模式的入口
    sid = (req(APP + '/api/sessions', 'POST', {'workspaceId': WS, 'mode': 'STANDARD'})
           .get('data') or {}).get('sessionId')
    r = req(APP + '/api/sessions/%s/mode' % sid, 'POST', {'mode': 'MINIMAL'})
    check('切到极简成功', r.get('success') and r.get('data') == 'MINIMAL', str(r.get('data')))
    r = req(APP + '/api/sessions/%s/mode' % sid, 'POST', {'mode': 'PTC'})
    check('★ 想切到 PTC 会被归一成标准', r.get('success') and r.get('data') == 'STANDARD', str(r.get('data')))
    r = req(APP + '/api/sessions/%s/mode' % sid, 'POST', {'mode': 'NOPE'})
    check('乱填模式名切换也报错', not r.get('success'), str(r.get('error'))[:80])
    after = {s['sessionId']: s for s in (req(APP + '/api/sessions').get('data') or [])}
    check('★ 切换后的模式落到了会话上（列表里是标准）',
          (after.get(sid) or {}).get('mode') == 'STANDARD',
          str((after.get(sid) or {}).get('mode')))

    # ④ 提示词预览入口也归一（防止从后门把 PTC 放回来）
    pv = req(APP + '/api/runtime/prompt-preview?mode=ptc&message=%E4%BD%A0%E5%A5%BD')
    check('★ 提示词预览里写 ptc 也算标准模式',
          pv.get('success') and (pv.get('data') or {}).get('mode') == 'STANDARD',
          str((pv.get('data') or {}).get('mode')))
    pv2 = req(APP + '/api/runtime/prompt-preview?mode=nope')
    check('提示词预览乱填模式名报错', not pv2.get('success'), str(pv2.get('error'))[:80])
    pv3 = req(APP + '/api/runtime/prompt-preview?mode=minimal&message=%E4%BD%A0%E5%A5%BD')
    check('提示词预览还能看极简模式', pv3.get('success') and (pv3.get('data') or {}).get('mode') == 'MINIMAL',
          str((pv3.get('data') or {}).get('mode')))
finally:
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    time.sleep(1)
    kill_port(APP_PORT)
    log.close()

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
