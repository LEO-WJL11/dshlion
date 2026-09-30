#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""A/B 对照：同一个 jar、同一个 HOME、同一个 tmpdir，只换 java 可执行文件，看谁能起。

用来定位"桌面版自带 JRE 在开发机上起不来"到底是：
  (a) 自带 JRE 本身有问题（打包/文件类型），还是
  (b) 这台机器上从工作区里跑 java 被环境限制住了。

用法：python tools/dev/_ab_java.py
"""

import os
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
BUNDLED = os.path.join(ROOT, 'desktop', 'electron', 'staging-backend', 'runtime-jre', 'bin', 'java.exe')
INSTALLED = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs', 'LionBox',
                         'runtime-jre', 'bin', 'java.exe')
TMP = os.path.join(os.environ.get('TEMP', '.'), '_abjava')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def probe(port):
    try:
        with urllib.request.urlopen('http://127.0.0.1:%d/api/runtime/mode' % port, timeout=3) as r:
            r.read()
        return True
    except Exception:
        return False


def run_trial(name, java, port):
    home = os.path.join(TMP, name)
    shutil.rmtree(home, ignore_errors=True)
    jtmp = os.path.join(home, 'jtmp')
    os.makedirs(jtmp, exist_ok=True)
    log_path = os.path.join(TMP, '%s.log' % name)
    with open(log_path, 'w', encoding='utf-8', errors='replace') as log:
        proc = subprocess.Popen([java, '-Dfile.encoding=UTF-8', '-Duser.home=' + home,
                                 '-Djava.io.tmpdir=' + jtmp, '-jar', JAR,
                                 '--server.port=%d' % port,
                                 '--lionbox.runtime.auto-download=false',
                                 '--lionbox.runtime.prewarm.enabled=false'],
                                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    up = False
    for _ in range(60):
        if probe(port):
            up = True
            break
        time.sleep(1)
    subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
    time.sleep(2)
    err = ''
    try:
        with open(log_path, encoding='utf-8', errors='replace') as f:
            lines = [l.strip() for l in f if 'Caused by' in l or 'AccessDenied' in l
                     or 'Unable to' in l or 'Started LionCodeApplication' in l]
        err = ' | '.join(lines[:3])
    except Exception:
        pass
    print('%-22s java=%-60s -> %s' % (name, java, 'OK' if up else 'FAIL'))
    if err:
        print('    %s' % err[:300])
    return up


def main():
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP, exist_ok=True)
    cands = [('A-bundled-copy', BUNDLED, 8895),
             ('B-installed', INSTALLED, 8896),
             ('C-system-java', os.environ.get('JAVA_HOME', '') + r'\bin\java.exe', 8897)]
    if not os.path.isfile(cands[2][1]):
        cands[2] = ('C-system-java', shutil.which('java') or 'java', 8897)
    for name, java, port in cands:
        if java.endswith('java.exe') and not os.path.isfile(java):
            print('%-22s 不存在：%s' % (name, java))
            continue
        run_trial(name, java, port)
    print()
    print('结论看上面三行：只有 A 失败 = 工作区里那份副本的问题（打包产物用安装目录那份验）；')
    print('A/C 都失败 = 环境限制；A/B 都 OK = 没问题。')


if __name__ == '__main__':
    main()
