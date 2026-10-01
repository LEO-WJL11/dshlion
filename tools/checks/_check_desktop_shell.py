#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""桌面版（Tauri 套壳）回归：验能在自动化环境里验的部分。

【这个文件原来验的是什么】最早桌面版试过一版手写的 C# WebView2 壳，当时的用例是
"起窗口 → 看它有没有来取页面 → UA 里有没有 WebView2"。那套现在删了：桌面版最终是
Tauri 套壳，而**窗口渲染这一步在本环境里根本验不了** —— WebView2 的宿主与浏览器进程
之间走命名管道通信，受限会话里起不来（实测 Tauri 报 0x8000FFFF、手写宿主直接 CLR 崩溃，
同一台机器上 Edge headless 却完全正常）。那条用例永远是红的，红得没有信息量。

【现在验什么】换成真正能自动判、判错了说明包真有问题的东西：
  1. 壳 exe 在，而且**只有几 MB**（体积就是"没打包 Chromium"的证据：Electron 那版解包 250MB+）；
  2. 壳跑 --selftest 能吐合法 JSON（地址解析 / 后端探测 / jar 与 JRE 定位 / WebView2 运行时）；
  3. 安装包在，而且**小于 100MB**（单文件放得进 GitHub，不用再切片）；
  4. 壳的工程文件别丢（tauri.conf.json、capabilities、图标、安装脚本）。

窗口里到底能不能显示界面，只能在真实桌面上双击看 —— 这条写在 docs/安装包清单.md 里，
不在这里假装验过。
"""

import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXE = os.path.join(ROOT, 'desktop', 'tauri', 'src-tauri', 'target', 'release',
                   'lionbox-desktop.exe')
SETUP = os.path.join(ROOT, 'installer', 'release', 'LionBox-Desktop-1.5.2-Setup.exe')
SELFTEST_OUT = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_shell_selftest.json')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

failed = []


def check(label, ok, detail=''):
    print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
    if not ok:
        failed.append(label)
    return ok


def main():
    # ---- 1) 壳 exe ----
    if not os.path.isfile(EXE):
        check('桌面壳 exe 在（Tauri 编出来的）', False, EXE)
        print('\n没编过就先跑：cd desktop/tauri && npx tauri build --no-bundle')
        return 1
    size = os.path.getsize(EXE)
    check('桌面壳 exe 在（Tauri 编出来的）', True, '%s 字节' % format(size, ','))
    check('★ 壳体积很小（没打包浏览器 —— 体积从 178MB 掉到 2.8MB 就靠这个）',
          size < 20 * 1024 * 1024, '%.2f MB' % (size / 1024.0 / 1024.0))

    # ---- 2) --selftest 的 JSON ----
    # 输出文件先试 %TEMP%：这个环境里 %TEMP% 偶尔不让写（会得到 exit=5，壳会明确报
    # "自检结果写不进去"），所以写不进去就退回到工作区里再跑一次 —— 别把"环境不让写"误判成"壳坏了"。
    env = dict(os.environ)
    env.setdefault('LIONBOX_URL', 'http://127.0.0.1:8080')
    data = {}
    rc = None
    for out in (SELFTEST_OUT, os.path.join(ROOT, '_shell_selftest.json')):
        if os.path.isfile(out):
            os.remove(out)
        try:
            proc = subprocess.run([EXE, '--selftest', '--out=' + out],
                                  env=env, capture_output=True, timeout=120)
            rc = proc.returncode
        except Exception as e:
            check('壳能跑起来（--selftest）', False, str(e))
            rc = None
            break
        if os.path.isfile(out):
            try:
                with open(out, encoding='utf-8') as f:
                    data = json.load(f)
            except Exception as e:
                check('自检结果能解析成 JSON', False, str(e))
            os.remove(out)
        if data or rc not in (5,):
            break

    # 0 = 后端在跑或能找到 jar；4 = 既没后端也找不到 jar（环境没装主程序，不算壳的错）；
    # 5 = 结果文件写不进去（环境不让写，换个地方已重试）
    check('壳能跑起来（--selftest）', rc in (0, 4, 5), 'exit=%s' % rc)
    check('★ 自检结果写成合法 JSON（GUI 程序没控制台，必须落文件）', bool(data),
          'result=%s' % data.get('result'))
    if data:
        check('壳解析后端地址正确', data.get('host') == '127.0.0.1' and data.get('port') == 8080,
              '%s:%s' % (data.get('host'), data.get('port')))
        check('★ 壳能判断后端在不在（本次：%s）'
              % ('在跑' if data.get('backend_up') else '没跑'),
              isinstance(data.get('backend_up'), bool))
        check('★ 壳能找到主程序的 jar（桌面版不自带后端，就靠这个）', bool(data.get('jar')),
              str(data.get('jar'))[-46:])
        check('★ 壳能找到主程序自带的 JRE', bool(data.get('java')), str(data.get('java'))[-46:])
        check('系统 WebView2 运行时可用（界面靠它渲染）',
              data.get('webview2_runtime') not in (None, '', 'none'), data.get('webview2_runtime'))

    # ---- 3) 安装包：单文件且 < 100MB ----
    if os.path.isfile(SETUP):
        ssize = os.path.getsize(SETUP)
        check('★ 桌面版安装包是单个文件（不用切片就能进仓库）', True,
              '%s 字节（%.2f MB）' % (format(ssize, ','), ssize / 1024.0 / 1024.0))
        check('★ 安装包 < 100MB（GitHub 单文件上限，超了就得走 Releases 或分片）',
              ssize < 100 * 1024 * 1024, '%.2f MB' % (ssize / 1024.0 / 1024.0))
    else:
        check('桌面版安装包在（ISCC installer\\LionBoxDesktop.iss）', False, SETUP)

    # ---- 4) 工程文件别丢 ----
    for rel in ('desktop/tauri/src-tauri/tauri.conf.json',
                'desktop/tauri/src-tauri/capabilities/default.json',
                'desktop/tauri/src-tauri/icons/icon.ico',
                'desktop/tauri/src-tauri/src/main.rs',
                'installer/LionBoxDesktop.iss'):
        check('工程文件在：' + rel, os.path.isfile(os.path.join(ROOT, rel)))

    print()
    print('结果：%s' % ('全部通过' if not failed else '失败 %d 项：%s' % (len(failed), failed)))
    print('（窗口渲染要在真实桌面双击看：WebView2 宿主在受限会话里起不来，本环境验不了 —— '
          '见 docs/安装包清单.md）')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
