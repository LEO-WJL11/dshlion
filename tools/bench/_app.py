#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""起一个**只属于本次测试**的应用实例（给 bench / 手工压测共用）。

为什么不直接用 target 里那个 fat jar：
  mvn package 最后一步是 spring-boot:repackage，它要把 jar 改名 —— 只要有任何一个
  进程（别人的测试实例）还开着那个 jar，Windows 就不让改名，整个 package 直接失败，
  而且会把 target 里的 fat jar 覆盖成 800KB 的瘦 jar，害得后面所有测试都起不来。
  所以这里默认走 `java -cp target/classes;<依赖包>` 直接跑主类：
  编译产出 classes 就够了，完全不碰 jar，谁在跑测试都不会互相卡。

用法：
    from _app import start_app, stop_app, req, wait_port
    proc = start_app(port=8919, home=HOME, config={...}, extra_args=[...])
"""

import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAIN_CLASS = 'com.lioncode.LionCodeApplication'
CP_FILE = os.path.join(ROOT, 'target', 'bench-cp.txt')
JAR = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def real_java():
    home = os.environ.get('JAVA_HOME')
    if home and os.path.isfile(os.path.join(home, 'bin', 'java.exe')):
        return os.path.join(home, 'bin', 'java.exe')
    return shutil.which('java') or 'java'


def classpath():
    """target/classes + 依赖包（依赖包从一个现成的 fat jar 里解出来）。

    【为什么要绕这一圈】Maven 的 dependency:build-classpath 要联网下插件，离线环境用不了。
    而 fat jar 里本来就装着全部依赖（BOOT-INF/lib/*.jar），直接从里面解出来就行 ——
    用已安装的那份（版本和 pom 一致）当来源，一次解开、长期复用，完全离线。

    最大的好处是：这么起应用**根本不碰 target 里的 jar**，谁在跑测试都不会锁住它，
    也就不会再出现"mvn package 改名失败 → fat jar 被覆盖成 800KB 瘦 jar"的连锁事故。
    """
    libs = os.path.join(ROOT, 'target', 'bench-libs')
    if not os.path.isdir(libs) or not os.listdir(libs):
        src = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs', 'LionBox',
                           'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
        if not os.path.isfile(src):
            # 退而求其次：仓库里任何一个 fat jar（超过 5MB 的）
            for cand in (JAR,):
                if os.path.isfile(cand) and os.path.getsize(cand) > 5 * 1024 * 1024:
                    src = cand
                    break
        if not os.path.isfile(src):
            raise SystemExit('找不到可用的 fat jar 来解依赖包；装一次 LionBox 或先 package 出 fat jar')
        os.makedirs(libs, exist_ok=True)
        with zipfile.ZipFile(src) as z:
            n = 0
            for name in z.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    with z.open(name) as fsrc, open(os.path.join(libs, os.path.basename(name)), 'wb') as fdst:
                        shutil.copyfileobj(fsrc, fdst)
                    n += 1
        print('[app] 从 %s 解出 %d 个依赖包到 target/bench-libs' % (os.path.basename(src), n))
    return os.path.join(ROOT, 'target', 'classes') + os.pathsep + os.path.join(libs, '*')


def _private_jar():
    """把 fat jar 复制成一份**本次进程私有**的副本再拿去跑。

    为什么要复制：Windows 上只要有一个 java 进程开着 target 里的那个 jar，
    `mvn package` 最后的 spring-boot:repackage 就没法把它改名成 .jar.original，
    于是 package 直接失败、而且 target 里会留下一个 800KB 的瘦 jar，
    后面所有 `java -jar target/...` 的测试全挂（这个坑今天踩了三次）。
    用私有副本之后，谁在跑测试都不会锁住 target 里的原件。
    """
    if not os.path.isfile(JAR):
        raise SystemExit('没有 jar：%s（先 python tools/dev/_mvn.py -o -q -DskipTests package）' % JAR)
    tmp = os.path.join(os.environ.get('TEMP', '.'), 'lionbox-bench-%d.jar' % os.getpid())
    shutil.copy2(JAR, tmp)
    return tmp


def write_config(home, config):
    """写应用配置。写之前先确保目录存在，并和已有配置合并（别把别的字段冲掉）。"""
    d = os.path.join(home, '.lioncode')
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, 'app-config.json')
    merged = {}
    if os.path.isfile(p):
        try:
            with open(p, encoding='utf-8') as f:
                merged = json.load(f)
        except Exception:
            merged = {}
    merged.update(config)
    with open(p, 'w', encoding='utf-8') as f:
        json.dump(merged, f, ensure_ascii=False)
    return p


def start_app(port, home, extra_args=None, log_path=None, use_jar=False):
    """启动应用，返回 (Popen, 日志文件对象)。调用方负责 stop_app。

    默认 use_jar=False → 走 `java -cp target/classes;依赖包` 主类启动：
    不需要 fat jar，也不锁 jar，跑多少次都不会影响别人的 mvn package。
    """
    args = [real_java(), '-Dfile.encoding=UTF-8', '-Duser.home=' + home]
    args += ['-jar', _private_jar()] if use_jar else ['-cp', classpath(), MAIN_CLASS]
    defaults = ['--server.port=%d' % port,
                '--lionbox.runtime.auto-download=false',
                '--lionbox.runtime.prewarm.enabled=false',
                # 【为什么默认关掉"改动人工审核"】出厂是开的（改文件先攒成待审、人点通过才落盘 ——
                # 这是用户要的产品行为）。但绝大多数用例验的是"工具能不能把文件改对"，
                # 开着审核文件根本不落盘，几十个用例会一起红 —— 那不是回归，是环境没配对。
                # 审核流程自己有专门的用例（_check_context_review.py），它显式把这个开关打开。
                '--lionbox.change-review.enabled=false']
    # 调用方在 extra_args 里给了同一个键，就以调用方为准 —— 否则 Spring 会把两个值拼成
    # "false,true" 然后启动失败（真实踩过：审核那个开关就是这么炸的）
    extra = list(extra_args or [])
    given = {a.split('=', 1)[0] for a in extra if a.startswith('--') and '=' in a}
    args += [d for d in defaults if d.split('=', 1)[0] not in given]
    args += extra
    log = open(log_path or os.path.join(home, 'app-%d.log' % port), 'w', encoding='utf-8', errors='replace')
    proc = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def stop_app(proc, log=None):
    if proc is not None and proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
    if log is not None:
        try:
            log.close()
        except Exception:
            pass


def req(url, data=None, timeout=60, method=None):
    body = None if data is None else json.dumps(data).encode('utf-8')
    r = urllib.request.Request(url, data=body, method=method,
                               headers={'Content-Type': 'application/json'} if body else {})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'replace'))


def wait_port(port, seconds=180, path='/api/runtime/mode'):
    url = 'http://127.0.0.1:%d%s' % (port, path)
    for _ in range(seconds):
        try:
            req(url, timeout=3)
            return True
        except Exception:
            time.sleep(1)
    return False


def kill_port(port):
    """把占用某端口的进程干掉（只能杀自己的测试端口，别对 8080/8788 用）。"""
    out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 5 and p[1].endswith(':' + str(port)) and p[3] == 'LISTENING':
            subprocess.run(['taskkill', '/F', '/PID', p[4]], capture_output=True)
    time.sleep(1)
