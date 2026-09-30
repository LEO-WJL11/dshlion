# -*- coding: utf-8 -*-
"""模型名冒烟检查（不加载模型、不碰 8788，用独立 user.home 跑在 8899）。

验的是三件事：
  1. 老配置里残留的底座模型名会被迁移成 lion-models1（含 providers 里登记的可选模型）；
  2. 自定义 API 实例里的同名模型不动（那是用户自己填的）；
  3. 界面/接口对外报出来的模型名就是 lion-models1。
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
LEGACY = 'MiMo-V2.6-Distill-Qwen-9B'
NEW = 'lion-models1'
PORT = 8899
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionnametest')
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


def check(label, cond, extra=''):
    global ok
    ok = ok and cond
    print(('  [OK]   ' if cond else '  [FAIL] ') + label + (('  ' + extra) if extra else ''))


def kill_port(port):
    try:
        out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
        pids = set()
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 5 and parts[1].endswith(':' + str(port)) and parts[3] == 'LISTENING':
                pids.add(parts[4])
        for pid in pids:
            subprocess.run(['taskkill', '/F', '/PID', pid], capture_output=True)
    except Exception:
        pass

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


old_cfg = {
    'providerMode': 'local',
    'provider': 'lionbox-local',
    'baseUrl': 'http://127.0.0.1:8788/v1',
    'model': LEGACY,
    'apiKey': '',
    'activeAdapter': 'OPENAI_COMPATIBLE',
    'openai': {'baseUrl': 'http://127.0.0.1:8788/v1', 'model': LEGACY, 'apiKey': ''},
    'providers': {
        'lionbox-local': {
            'providerId': 'lionbox-local', 'displayName': 'LionBox 本地模型',
            'baseUrl': 'http://127.0.0.1:8788/v1', 'apiKey': '',
            'availableModels': [{'modelId': LEGACY, 'modelName': LEGACY, 'selected': True}],
        },
        'lionbox-custom': {
            'providerId': 'lionbox-custom', 'displayName': '自定义 API',
            'baseUrl': 'https://api.xiaomimimo.com/v1', 'apiKey': 'K',
            'availableModels': [{'modelId': LEGACY, 'modelName': LEGACY, 'selected': False}],
        },
    },
}

print('=' * 74)
print('模型名冒烟检查：老配置迁移 + 对外暴露的名字')
print('=' * 74)

shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(os.path.dirname(CFG))
with open(CFG, 'w', encoding='utf-8') as f:
    json.dump(old_cfg, f, ensure_ascii=False, indent=2)
print('已造一份老配置（模型名 = %s）' % LEGACY)

kill_port(PORT)
proc = None
log = open(LOG, 'w', encoding='utf-8', errors='replace')
try:
    proc = subprocess.Popen(
        [real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + HOME, '-jar', JAR,
         '--server.port=%d' % PORT, '--lionbox.runtime.auto-download=false'],
        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    print('启动应用 pid=%d（端口 %d，auto-download=false）...' % (proc.pid, PORT))

    up = False
    for _ in range(90):
        if proc.poll() is not None:
            break
        try:
            with urllib.request.urlopen('http://127.0.0.1:%d/api/runtime/local' % PORT, timeout=3) as r:
                if r.status == 200:
                    up = True
                    break
        except Exception:
            time.sleep(1)
    check('应用起来了（%d 端口可访问）' % PORT, up, '' if up else '看 ' + LOG)
    if not up:
        raise SystemExit(1)

    # 等配置落盘
    time.sleep(2)
    with open(CFG, encoding='utf-8') as f:
        new = json.load(f)

    print('— 1) 老配置迁移 —')
    check('顶层 model = %s' % NEW, new.get('model') == NEW, '实际 %s' % new.get('model'))
    check('openai.model = %s' % NEW, (new.get('openai') or {}).get('model') == NEW,
          '实际 %s' % (new.get('openai') or {}).get('model'))
    lm = (((new.get('providers') or {}).get('lionbox-local') or {}).get('availableModels') or [{}])[0]
    check('本地实例可选模型 modelId = %s' % NEW, lm.get('modelId') == NEW, '实际 %s' % lm.get('modelId'))
    check('本地实例可选模型 modelName = %s' % NEW, lm.get('modelName') == NEW, '实际 %s' % lm.get('modelName'))

    print('— 2) 自定义实例不动 —')
    cm = (((new.get('providers') or {}).get('lionbox-custom') or {}).get('availableModels') or [{}])[0]
    check('自定义实例里的同名模型保持原样（是用户自己填的）',
          cm.get('modelId') == LEGACY and cm.get('modelName') == LEGACY,
          '实际 %s' % cm.get('modelId'))

    print('— 3) 对外暴露的名字 —')
    with open(LOG, encoding='utf-8', errors='replace') as f:
        text = f.read()
    check('日志里有迁移记录', '历史配置中的模型名已更新' in text)
    with urllib.request.urlopen('http://127.0.0.1:%d/api/runtime/local' % PORT, timeout=5) as r:
        status = r.read().decode('utf-8', 'replace')
    check('接口 /api/runtime/local 里不含底座名', LEGACY not in status)
    with urllib.request.urlopen('http://127.0.0.1:%d/' % PORT, timeout=5) as r:
        page = r.read().decode('utf-8', 'replace')
    check('页面里不含底座名', LEGACY not in page)
    check('页面里有 %s' % NEW, NEW in page)
finally:
    log.close()
    if proc is not None and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    time.sleep(1)
    kill_port(PORT)
    print('已清理：应用进程与 %d 端口' % PORT)

print('=' * 74)
print('结果：' + ('全部通过' if ok else '有失败项'))
sys.exit(0 if ok else 1)
