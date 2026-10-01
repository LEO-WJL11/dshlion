#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验 1.5.4：**只有一个包**，装完 VS Code 里就多了 LionBox 插件。

用户的话："就把它跟 VS Code 的打成一个包，然后原来其他的版本都不要了。"
所以这份验证要证明三件事：
  1. 包里真的有 VS Code 插件，而且装完**自动装进了 VS Code**（用 code --list-extensions 查，
     不是看安装脚本"写了会装"）；
  2. 本体照旧能用（装出来的 jar + 自带 JRE 起服务，页面/接口都通）；
  3. 卸载时插件也跟着卸掉（别在编辑器里留一个连不上后端的空面板）。
另外还要证明"其他版本真的不要了"：release 目录里只有一个 exe。

用法：python tools/release/_verify_154_single.py
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join(ROOT, 'installer', 'release')
SETUP = os.path.join(REL, 'LionBox-Setup-1.5.4.exe')
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_154_install')
HOME = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_154_home')
PORT = 8949
EXT_ID = 'lioncode.lionbox'

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

ok = True


def check(label, cond, detail=''):
    global ok
    ok = ok and bool(cond)
    print('%s %s%s' % ('[OK]  ' if cond else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))


def code_cli():
    """VS Code 命令行：官方版默认不在 PATH 里，得按常见位置找"""
    cands = [
        os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs', 'Microsoft VS Code', 'bin', 'code.cmd'),
        r'C:\Program Files\Microsoft VS Code\bin\code.cmd',
        r'C:\Program Files (x86)\Microsoft VS Code\bin\code.cmd',
    ]
    for c in cands:
        if os.path.isfile(c):
            return c
    return shutil.which('code')


def list_extensions(code):
    if not code:
        return None
    r = subprocess.run([code, '--list-extensions'], capture_output=True, text=True,
                       errors='replace', timeout=120, shell=False)
    return [x.strip().lower() for x in (r.stdout or '').splitlines() if x.strip()]


def port_free(p):
    s = socket.socket()
    try:
        s.bind(('127.0.0.1', p))
        return True
    except OSError:
        return False
    finally:
        s.close()


def get(path, timeout=10):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (PORT, path), timeout=timeout) as r:
        return r.read()


def main():
    # ---- 0) "只有一个包" ----
    files = sorted(os.listdir(REL)) if os.path.isdir(REL) else []
    exes = [f for f in files if f.endswith('.exe')]
    check('★ release 目录里只有一个安装包', len(exes) == 1, ', '.join(files))
    check('★ 那个包就是 LionBox-Setup-1.5.4.exe', exes == ['LionBox-Setup-1.5.4.exe'], exes)
    for gone in ('LionBox-Desktop-1.5.4-Setup.exe', 'LionBox-JetBrains-1.5.4.zip',
                 'LionBox-VSCode-1.5.4.vsix'):
        check('废弃的版本不再单独出包：%s' % gone, gone not in files)
    check('桌面版源码已删（desktop/tauri）', not os.path.isdir(os.path.join(ROOT, 'desktop', 'tauri')))
    check('JetBrains 插件源码已删（extensions/jetbrains）',
          not os.path.isdir(os.path.join(ROOT, 'extensions', 'jetbrains')))
    if not os.path.isfile(SETUP):
        print('没找到 %s' % SETUP)
        return 1
    print('安装包：%s  %.2f MB' % (os.path.basename(SETUP), os.path.getsize(SETUP) / 1048576.0))

    code = code_cli()
    check('这台机器有 VS Code 命令行（自动装插件要用）', bool(code), code or '没找到')
    before = list_extensions(code) or []
    check('装之前 VS Code 里没有 %s（后面才能证明是这次装上的）' % EXT_ID, EXT_ID not in before,
          '现有 %d 个扩展' % len(before))

    if not port_free(PORT):
        raise SystemExit('端口 %d 被占了' % PORT)

    # ---- 1) 静默安装 ----
    run_dir = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_154_setup')
    shutil.rmtree(run_dir, ignore_errors=True)
    os.makedirs(run_dir, exist_ok=True)
    run_exe = os.path.join(run_dir, os.path.basename(SETUP))
    shutil.copy2(SETUP, run_exe)     # 从工作区里跑 Inno 的自解压会被这个环境挡掉
    shutil.rmtree(TMP, ignore_errors=True)
    shutil.rmtree(HOME, ignore_errors=True)
    os.makedirs(HOME, exist_ok=True)

    r = subprocess.run([run_exe, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS',
                        '/DIR=' + TMP], capture_output=True, timeout=900)
    check('静默安装返回 0', r.returncode == 0, 'exit=%d' % r.returncode)

    jar = None
    for f in (os.listdir(TMP) if os.path.isdir(TMP) else []):
        if f.startswith('lion-code-agent-harness') and f.endswith('.jar'):
            jar = os.path.join(TMP, f)
    java = os.path.join(TMP, 'runtime-jre', 'bin', 'java.exe')
    check('本体装好了（jar + 自带 JRE + 技能）',
          bool(jar) and os.path.isfile(java) and os.path.isdir(os.path.join(TMP, 'skills')))

    # ---- 2) 包里的 VS Code 插件 + 是否真装上了 ----
    vsix_in_app = os.path.join(TMP, 'vscode-extension', 'LionBox-VSCode.vsix')
    helper = os.path.join(TMP, 'install-vscode-ext.bat')
    result_txt = os.path.join(TMP, 'vscode-extension', 'install-result.txt')
    check('★ 包里带着 VS Code 插件', os.path.isfile(vsix_in_app),
          ('%s 字节' % os.path.getsize(vsix_in_app)) if os.path.isfile(vsix_in_app) else '缺')
    check('安装辅助脚本就位', os.path.isfile(helper))
    check('★ 安装过程留下了结果记录（能查"到底装没装"）', os.path.isfile(result_txt),
          result_txt)
    log = ''
    if os.path.isfile(result_txt):
        log = open(result_txt, encoding='utf-8', errors='replace').read()
        print('   ---- 安装结果.txt ----')
        for line in log.strip().splitlines():
            print('   | ' + line)
    check('结果记录里没报失败', '失败' not in log or '装好了' in log, log.strip()[:120])

    after = list_extensions(code) or []
    check('★ VS Code 里真的装上了 LionBox 插件（code --list-extensions 查得到）',
          EXT_ID in after, '扩展数 %d → %d' % (len(before), len(after)))

    # ---- 3) 本体能用 ----
    proc = None
    try:
        if jar and os.path.isfile(java):
            env = dict(os.environ)
            env['USERPROFILE'] = HOME
            env['HOME'] = HOME
            proc = subprocess.Popen(
                [java, '-Duser.home=' + HOME, '-Djava.io.tmpdir=' + HOME, '-jar', jar,
                 '--server.port=%d' % PORT, '--lionbox.headless=true',
                 '--lionbox.runtime.auto-download=false'],
                cwd=TMP, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            up = False
            for _ in range(120):
                time.sleep(1)
                try:
                    get('/api/runtime/mode', timeout=2)
                    up = True
                    break
                except Exception:
                    if proc.poll() is not None:
                        break
            check('装出来的后端起来了', up)
            if up:
                page = get('/index.html', timeout=15).decode('utf-8')
                check('页面里仍有待审改动区 + 窗口指示', 'id="changeBox"' in page and 'id="ctxChip"' in page)
                ctx = json.loads(get('/api/context', timeout=10).decode('utf-8')).get('context', {})
                check('上下文窗口默认仍是 16K', ctx.get('defaultLimit') == 16384, str(ctx))
                ch = json.loads(get('/api/changes', timeout=10).decode('utf-8'))
                check('改动人工审核默认仍是开', ch.get('enabled') is True)
    finally:
        if proc and proc.poll() is None:
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)

    # ---- 4) 卸载：本体 + 插件一起走 ----
    un = os.path.join(TMP, 'unins000.exe')
    if os.path.isfile(un):
        subprocess.run([un, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'],
                       capture_output=True, timeout=600)
        for _ in range(30):
            time.sleep(1)
            if not os.path.isdir(TMP) or not os.listdir(TMP):
                break
        check('卸载后安装目录清空', not os.path.isdir(TMP) or not os.listdir(TMP),
              os.listdir(TMP) if os.path.isdir(TMP) else '')
    # VS Code 卸扩展是**异步**的：--uninstall-extension 返回之后，列表里可能还挂着它一会儿。
    # 所以这里轮询等，而不是查一次就判失败（第一版就是这么误报的）。
    left = list_extensions(code) or []
    for _ in range(30):
        if EXT_ID not in left:
            break
        time.sleep(1)
        left = list_extensions(code) or []
    check('★ 卸载时 VS Code 插件也一起卸掉了', EXT_ID not in left, '剩下 %d 个扩展' % len(left))
    check('卸载记录写到了 %TEMP%（证明卸载脚本真的被调用了）',
          os.path.isfile(os.path.join(os.environ.get('TEMP', '.'), 'lionbox-vscode-uninstall.txt')))

    shutil.rmtree(run_dir, ignore_errors=True)
    shutil.rmtree(HOME, ignore_errors=True)
    print()
    print('结果：%s' % ('全部通过' if ok else '有失败项'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
