# -*- coding: utf-8 -*-
"""工具通道选择的行为测试（不加载模型，8899 端口）。

验的是 useNativeTools() 在四种组合下的结果：
  1) 本地模式 + auto   -> 文本 <tool_call>（llama-server 没开 --jinja，下发 tools 会被丢掉，
                          而且模型本来就是按文本约定微调的）
  2) 自定义 API + auto -> 原生 function calling
  3) 本地模式 + 显式 native -> 尊重用户显式选择，仍走原生
  4) 自定义 API + 显式 text -> 强制文本
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
PORT = 8899
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionchannel')
HOME = os.path.join(TMP, 'home')
CFG = os.path.join(HOME, '.lioncode', 'app-config.json')
LOG = os.path.join(TMP, 'app.log')
ok = True


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


def get(path, timeout=20):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (PORT, path), timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def post(path, body, timeout=20):
    req = urllib.request.Request('http://127.0.0.1:%d%s' % (PORT, path),
                                 data=json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def native_now():
    d = get('/api/runtime/prompt-preview?mode=standard')
    p = d.get('data') or d
    return bool(p.get('nativeTools'))


def check(label, got, want):
    global ok
    good = got == want
    ok = ok and good
    print('  %s %s（期望 %s，实际 %s）' % ('[OK]  ' if good else '[FAIL]', label, want, got))


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


def write_cfg(provider_mode, base_url, model, tool_mode=None):
    cfg = {'providerMode': provider_mode, 'baseUrl': base_url, 'model': model, 'apiKey': '',
           'provider': 'lionbox-local' if provider_mode == 'local' else 'lionbox-custom'}
    if tool_mode:
        cfg['toolCallMode'] = tool_mode
    os.makedirs(os.path.dirname(CFG), exist_ok=True)
    with open(CFG, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, ensure_ascii=False)


print('=' * 70)
print('工具通道选择测试（useNativeTools）')
print('=' * 70)
shutil.rmtree(TMP, ignore_errors=True)
write_cfg('local', 'http://127.0.0.1:8788/v1', 'lion-models1')
kill_port(PORT)
log = open(LOG, 'w', encoding='utf-8', errors='replace')
proc = None
try:
    proc = subprocess.Popen([real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
                             '--server.port=%d' % PORT, '--lionbox.runtime.auto-download=false',
                             # 关掉改动人工审核：这些用例验的是"工具能不能把文件改对"，开着审核
                             # 文件根本不会落盘（出厂默认是开的，见 tools/bench/_app.py 的说明）
                             '--lionbox.change-review.enabled=false',
                             '--lionbox.runtime.prewarm.enabled=false'],
                            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    up = False
    for _ in range(90):
        try:
            get('/api/runtime/mode', timeout=3)
            up = True
            break
        except Exception:
            time.sleep(1)
    if not up:
        print('应用没起来，看 ' + LOG)
        raise SystemExit(1)

    # 本地模式走**文本**通道：软件自带的 llama-server 会把模型一次吐的多个 <tool_call> 块
    # 揉成一个调用、把后续块的 XML 塞进第一个调用的 arguments，JSON 解析失败 → 整轮作废。
    # 抓包实测见 AgentLoop.useNativeTools() 注释；文本通道下多块由我们自己的解析器处理，干净。
    check('本地模式 + auto -> 文本（服务端会揉坏多个块）', native_now(), False)

    post('/api/runtime/tool-call-mode', {'mode': 'native'})
    check('本地模式 + 显式 native -> 原生（用户可强制）', native_now(), True)

    post('/api/runtime/tool-call-mode', {'mode': 'auto'})
    check('切回 auto 后又是文本', native_now(), False)

    # 切到自定义 API：同样走原生
    post('/api/runtime/mode', {'mode': 'custom', 'baseUrl': 'https://api.xiaomimimo.com/v1',
                               'apiKey': 'dummy', 'model': 'mimo-v2.6-flash'})
    check('自定义 API + auto -> 原生', native_now(), True)

    post('/api/runtime/tool-call-mode', {'mode': 'text'})
    check('自定义 API + 显式 text -> 文本', native_now(), False)
finally:
    log.close()
    if proc is not None and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    time.sleep(1)
    kill_port(PORT)
    print('已清理 8899')

print('=' * 70)
print('结果：' + ('全部通过' if ok else '有失败项'))
sys.exit(0 if ok else 1)
