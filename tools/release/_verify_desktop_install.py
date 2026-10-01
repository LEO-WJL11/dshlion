#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验桌面版安装包：装一遍 → 看文件在不在 → 跑装出来的 exe 自检 → 卸载。

【为什么要单独验这一套】桌面版现在是 Tauri 套壳（安装包 2.84MB），它和主程序是"壳 + 后端"的
关系：壳自己要能起来、能找到主程序装好的 jar 和 JRE。这里把装/跑/卸三步都过一遍，
而且**跑的是装出来的那份 exe**（不是仓库里那份），避免"源码对了、装出来的不对"。

窗口渲染这一步这里**验不了**：WebView2 的宿主需要命名管道与浏览器进程通信，
在受限的自动化环境里起不来（实测 Tauri 报 0x8000FFFF、手写的 .NET 宿主直接 CLR 崩溃，
而同一台机器上 Edge headless 是正常的）。所以这里验的是"壳的逻辑 + 安装包内容"，
窗口那一下要在真实桌面上双击看。

用法：python tools/release/_verify_desktop_install.py
"""

import json
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXE = os.path.join(ROOT, 'installer', 'release', 'LionBox-Desktop-1.5.1-Setup.exe')
# 装到 %TEMP% 而不是仓库里 —— 和用户"装到自己用户目录"最接近。
# 而且 Inno 的安装/卸载程序都要先把自己复制到 %TEMP% 再干活：在工作区里跑会被这个环境挡住，
# 表现成"装了没反应 / 卸了没反应"，极易误判成包坏了（实测：同一个 exe 放 %TEMP% 跑就正常）。
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_desktop_test')
LOG = os.path.join(TMP + '_setup.log')
SELFTEST = os.path.join(TMP + '_selftest.json')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def check(label, ok, detail=''):
    print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
    return ok


def main():
    ok = True
    if not os.path.isfile(EXE):
        print('没找到 %s（先跑 ISCC installer\\LionBoxDesktop.iss）' % EXE)
        return 1
    print('安装包：%s  %s 字节（%.2f MB）'
          % (os.path.basename(EXE), format(os.path.getsize(EXE), ','),
             os.path.getsize(EXE) / 1024.0 / 1024.0))

    shutil.rmtree(TMP, ignore_errors=True)
    for f in (LOG, SELFTEST):
        if os.path.isfile(f):
            os.remove(f)

    # 【为什么先把安装包拷到 %TEMP% 再跑】Inno 的安装包是自解压程序，启动时会把自己解到
    # %TEMP%\is-XXXX.tmp。在**工作区里**直接运行它时，这一步会被这个环境挡掉，
    # 表现是 exit=1、连日志都不写、一个文件都不装 —— 看起来像"包坏了"，其实包是好的
    # （同一个 exe 拷到 %TEMP% 跑就一切正常，实测过）。所以这里先拷出来再装。
    # 用户在自己机器上双击 installer\release 里的包没有这个问题。
    run_dir = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_setup_run')
    shutil.rmtree(run_dir, ignore_errors=True)
    os.makedirs(run_dir, exist_ok=True)
    run_exe = os.path.join(run_dir, os.path.basename(EXE))
    shutil.copy2(EXE, run_exe)

    r = subprocess.run([run_exe, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS',
                        '/DIR=' + TMP, '/LOG=' + LOG], capture_output=True, timeout=300)
    ok &= check('静默安装返回 0', r.returncode == 0, 'exit=%d' % r.returncode)

    app = os.path.join(TMP, 'LionBox.exe')
    ok &= check('装出了 LionBox.exe', os.path.isfile(app),
                ('%s 字节' % format(os.path.getsize(app), ',')) if os.path.isfile(app) else '缺文件')
    ok &= check('装了图标', os.path.isfile(os.path.join(TMP, 'icon.ico')))
    ok &= check('卸载器在', os.path.isfile(os.path.join(TMP, 'unins000.exe')))

    if os.path.isfile(app):
        # 跑装出来的那份 exe 的自检：地址解析 / 后端探测 / jar 与 JRE 定位 / WebView2 运行时
        env = dict(os.environ)
        env['LIONBOX_URL'] = env.get('LIONBOX_URL', 'http://127.0.0.1:8080')
        subprocess.run([app, '--selftest', '--out=' + SELFTEST], env=env, timeout=120,
                       capture_output=True)
        data = {}
        if os.path.isfile(SELFTEST):
            with open(SELFTEST, encoding='utf-8') as f:
                data = json.load(f)
        ok &= check('装出来的 exe 自检跑通（JSON 可解析）', bool(data),
                    'result=%s' % data.get('result'))
        ok &= check('壳能解析后端地址', data.get('host') == '127.0.0.1' and data.get('port') == 8080,
                    '%s:%s' % (data.get('host'), data.get('port')))
        ok &= check('壳能定位主程序的 jar', bool(data.get('jar')), str(data.get('jar'))[-40:])
        ok &= check('壳能定位主程序自带的 JRE', bool(data.get('java')), str(data.get('java'))[-40:])
        ok &= check('系统 WebView2 运行时在（界面就靠它渲染）',
                    data.get('webview2_runtime') not in (None, '', 'none'),
                    data.get('webview2_runtime'))

    un = os.path.join(TMP, 'unins000.exe')
    if os.path.isfile(un):
        subprocess.run([un, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'],
                       capture_output=True, timeout=300)
        # 【为什么要等一下】Inno 的卸载器会把自己复制到临时目录再干活，原进程立刻就返回了，
        # 所以"卸载完"这一刻目录可能还没删干净。等一下再判，别把正常行为当成失败。
        gone = False
        for _ in range(30):
            time.sleep(1)
            if not os.path.isdir(TMP) or not os.listdir(TMP):
                gone = True
                break
        left = os.listdir(TMP) if os.path.isdir(TMP) else []
        ok &= check('卸载后安装目录被清掉', gone, ('残留: %s' % left) if left else '')

    shutil.rmtree(TMP, ignore_errors=True)
    for f in (LOG, SELFTEST):
        if os.path.isfile(f):
            os.remove(f)
    print()
    print('结果：%s' % ('全部通过' if ok else '有失败项'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
