#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验一下"桌面版（Electron）随包自带的后端"能不能真的起来。

为什么要单独验：桌面版是独立安装包，用户机器上不一定有 Java —— 包里那份
jlink 精简 JRE 是它唯一的运行时。这个脚本就用**包里那个 java.exe** 起包里那个 jar，
确认端口能通、技能和插件列表都在（技能是随包发在 backend/skills 下的，
路径依赖"jar 同级目录"这条规则，最容易在打包后失效）。

用法：python tools/release/_verify_desktop_bundle.py [解包目录]
默认解包目录：desktop\\electron\\dist\\win-unpacked\\resources\\backend
"""

import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_BACKEND = os.path.join(ROOT, 'desktop', 'electron', 'dist', 'win-unpacked',
                               'resources', 'backend')
PORT = 8888
TMP = os.path.join(os.environ.get('TEMP', '.'), '_liondesktopverify')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def req(path, timeout=30):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (PORT, path), timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8', 'replace'))


def main():
    backend = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BACKEND
    java = os.path.join(backend, 'runtime-jre', 'bin', 'java.exe')
    jar = os.path.join(backend, 'lion-code-agent-harness.jar')
    failed = []

    def check(label, ok, detail=''):
        print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
        if not ok:
            failed.append(label)

    check('包里有自带 JRE', os.path.isfile(java), java)
    check('包里有后端 jar', os.path.isfile(jar),
          '%s 字节' % format(os.path.getsize(jar), ',') if os.path.isfile(jar) else '')
    check('包里有内置技能', os.path.isfile(os.path.join(backend, 'skills', 'backend', 'SKILL.md')))
    check('包里有本地模型运行时',
          os.path.isfile(os.path.join(backend, 'runtime-vulkan', 'llama-server.exe')))
    if failed:
        return 1

    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP, exist_ok=True)
    jtmp = os.path.join(TMP, 'jtmp')
    os.makedirs(jtmp, exist_ok=True)
    log = open(os.path.join(TMP, 'boot.log'), 'w', encoding='utf-8', errors='replace')
    # 【为什么要显式给 java.io.tmpdir】Tomcat 启动时会在 java.io.tmpdir 下建 tomcat.<端口>.<随机>
    # 临时目录；默认取的是系统 TEMP，而这个环境里系统 TEMP 下**新建目录会被拒**
    # （java.nio.file.AccessDeniedException: ...\Temp\tomcat.8888.xxxx），
    # 于是后端直接起不来。给它一个自己的 tmpdir 既绕开这个问题，也是打包应用该做的事：
    # 打包后不该把临时文件丢进系统的 %TEMP%（用户清一次临时文件就可能删到运行中的东西）。
    proc = subprocess.Popen([java, '-Dfile.encoding=UTF-8', '-Duser.home=' + TMP,
                             '-Djava.io.tmpdir=' + jtmp, '-jar', jar,
                             '--server.port=%d' % PORT,
                             '--lionbox.runtime.auto-download=false',
                             '--lionbox.runtime.prewarm.enabled=false'],
                            cwd=backend, stdout=log, stderr=subprocess.STDOUT)
    try:
        up = False
        for _ in range(120):
            try:
                req('/api/runtime/mode', timeout=3)
                up = True
                break
            except Exception:
                time.sleep(1)
        check('用包里的 JRE 能起后端（不打系统 Java）', up)
        if up:
            sk = req('/api/skills')
            skills = sk.get('skills') or (sk.get('data') or {}).get('skills') or []
            check('技能列表里有内置四个（说明 backend/skills 被认出来了）',
                  len(skills) >= 4, [s.get('id') for s in skills][:6])
            pl = req('/api/plugins')
            plugins = pl.get('plugins') or (pl.get('data') or {}).get('plugins') or []
            check('插件列表正常', len(plugins) >= 50, '%d 个' % len(plugins))
            with urllib.request.urlopen('http://127.0.0.1:%d/' % PORT, timeout=20) as r:
                page = r.read().decode('utf-8', 'replace')
            check('WebUI 页面发得出来（桌面窗口加载的就是它）',
                  'data-theme' in page and len(page) > 100000, '%d 字符' % len(page))
    finally:
        if proc.poll() is None:
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        log.close()

    print()
    if failed:
        print('失败项：%s' % failed)
        print('日志：%s' % os.path.join(TMP, 'boot.log'))
        return 1
    print('桌面版随包后端：全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
