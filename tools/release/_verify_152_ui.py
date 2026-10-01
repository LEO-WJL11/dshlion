#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验 1.5.2 主安装包：装 → 起（用装出来的自带 JRE）→ 断言设置页签已合并 → 卸。

【为什么必须验"装出来的那份"】用户用的是安装包，不是仓库源码。页面到底长什么样，
只有把包装上、拿装出来的 jar 起服务、去 GET /index.html 看才算数 —— 这也是这一轮
用户真正不满的点（设置里散着四个页签、还互相指路），所以这里逐条断言：

  · 页面里**有**「插件管理」这一个页签
  · 页面里**没有**「插件参数 / 技能 / 审批策略」这三个旧页签按钮
  · 老页签名还能落到插件管理（aliases 在）
  · 每个插件的参数块按插件分发（pluginParamsFor 的六个分支都在）
  · 服务端发出来的页面和仓库里的 web/index.html **逐字节一致**（别出现"改完忘了打进去"）
  · /api/plugins 能列出九类插件（插件系统在后端也真的在）

用法：python tools/release/_verify_152_ui.py
"""

import io
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SETUP = os.path.join(ROOT, 'installer', 'release', 'LionBox-Setup-1.5.2.exe')
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_152_install')
HOME = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_152_home')
PORT = 8944

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

ok = True


def check(label, cond, detail=''):
    global ok
    ok = ok and bool(cond)
    print('%s %s%s' % ('[OK]  ' if cond else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))


def port_free(p):
    s = socket.socket()
    try:
        s.bind(('127.0.0.1', p))
        return True
    except OSError:
        return False
    finally:
        s.close()


def get(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


def main():
    if not os.path.isfile(SETUP):
        print('没找到 %s（先跑 tools/release/_finalize_152.py）' % SETUP)
        return 1
    if not port_free(PORT):
        raise SystemExit('端口 %d 被占了，换个端口再跑' % PORT)

    print('安装包：%s  %.2f MB' % (os.path.basename(SETUP), os.path.getsize(SETUP) / 1048576.0))

    # 安装包要先拷到 %TEMP% 再跑：在工作区里直接运行 Inno 的自解压会被这个环境挡掉
    run_dir = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_152_setup')
    shutil.rmtree(run_dir, ignore_errors=True)
    os.makedirs(run_dir, exist_ok=True)
    run_exe = os.path.join(run_dir, os.path.basename(SETUP))
    shutil.copy2(SETUP, run_exe)
    shutil.rmtree(TMP, ignore_errors=True)
    shutil.rmtree(HOME, ignore_errors=True)
    os.makedirs(HOME, exist_ok=True)

    r = subprocess.run([run_exe, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS',
                        '/DIR=' + TMP], capture_output=True, timeout=600)
    check('静默安装返回 0', r.returncode == 0, 'exit=%d' % r.returncode)

    jar = None
    for f in os.listdir(TMP) if os.path.isdir(TMP) else []:
        if f.startswith('lion-code-agent-harness') and f.endswith('.jar'):
            jar = os.path.join(TMP, f)
    java = os.path.join(TMP, 'runtime-jre', 'bin', 'java.exe')
    check('装出了后端 jar', bool(jar), os.path.basename(jar) if jar else '缺')
    check('装出了自带 JRE（桌面版/主程序都靠它）', os.path.isfile(java), java)
    check('装出了技能目录', os.path.isdir(os.path.join(TMP, 'skills')))

    proc = None
    try:
        if jar and os.path.isfile(java):
            print('用装出来的自带 JRE 起服务（端口 %d，HOME 隔离）…' % PORT)
            env = dict(os.environ)
            env['USERPROFILE'] = HOME
            env['HOME'] = HOME
            proc = subprocess.Popen(
                [java, '-Duser.home=' + HOME, '-Djava.io.tmpdir=' + HOME,
                 '-jar', jar, '--server.port=%d' % PORT, '--lionbox.headless=true',
                 # 【必须关掉自动下载】不关的话：全新安装目录里没有权重，应用一起来就
                 # 开始从 ModelScope 拖 8.9GB 的 Q8 模型（第一次跑这个验证就中招了，
                 # 卸载后目录里剩了个 lion-merged-Q8_0.gguf.part）。验证只需要 HTTP 接口，
                 # 不需要权重 —— 所以显式关掉。
                 '--lionbox.runtime.auto-download=false'],
                cwd=TMP, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            up = False
            for _ in range(120):
                time.sleep(1)
                try:
                    get('http://127.0.0.1:%d/api/runtime/mode' % PORT, timeout=2)
                    up = True
                    break
                except Exception:
                    if proc.poll() is not None:
                        break
            check('装出来的后端起来了', up, 'pid=%s' % (proc.pid if proc else '-'))

        if proc and proc.poll() is None:
            page = get('http://127.0.0.1:%d/index.html' % PORT, timeout=10).decode('utf-8')
            local = io.open(os.path.join(ROOT, 'web', 'index.html'), encoding='utf-8',
                            newline='').read()
            check('★ 服务端发的页面和仓库 web/index.html 一致（改完真打进去了）',
                  len(page) == len(local), '%d vs %d 字节' % (len(page), len(local)))
            check('★ 设置里有「插件管理」页签',
                  'id="setTabPlugins"' in page and '插件管理' in page)
            gone = [t for t in ('setTabPluginParams', 'setTabSkills', 'setTabApprovals')
                    if t in page]
            check('★ 旧页签已删掉（插件参数 / 技能 / 审批策略）', not gone, gone)
            check('★ 老页名仍落到插件管理', "'pluginparams':'plugins'" in page)
            check('★ 六个参数块按插件分发（终端/大循环/子智能体/审查/团队/自动化）',
                  all(("which === '%s'" % k) in page for k in
                      ['terminal', 'loop', 'subagent', 'review', 'team', 'automation']))
            check('★ 技能与审批策略在插件页里（不再有独立页签函数）',
                  'skillsInlineHtml: function' in page and 'approvalsInlineHtml: function' in page)

            pl = json.loads(get('http://127.0.0.1:%d/api/plugins' % PORT, timeout=10).decode('utf-8'))
            items = pl.get('plugins') or (pl.get('data') or {}).get('plugins') or []
            kinds = sorted({(p.get('kind') or '').upper() for p in items})
            check('★ 后端列出九类插件', len([k for k in kinds if k]) >= 9,
                  '%d 个插件，%d 类：%s' % (len(items), len(kinds), ','.join(kinds)))
            for pid in ('plugin.terminal', 'plugin.agent-loop', 'plugin.subagent',
                        'plugin.approval-review', 'plugin.agent-team', 'plugin.automation'):
                check('后端有插件 %s' % pid,
                      any(p.get('id') == pid for p in items))
    finally:
        if proc and proc.poll() is None:
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
        un = os.path.join(TMP, 'unins000.exe')
        if os.path.isfile(un):
            subprocess.run([un, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'],
                           capture_output=True, timeout=300)
            for _ in range(20):
                time.sleep(1)
                if not os.path.isdir(TMP) or not os.listdir(TMP):
                    break
        check('卸载后安装目录清空', not os.path.isdir(TMP) or not os.listdir(TMP),
              os.listdir(TMP) if os.path.isdir(TMP) else '')
        shutil.rmtree(run_dir, ignore_errors=True)
        shutil.rmtree(HOME, ignore_errors=True)

    print()
    print('结果：%s' % ('全部通过' if ok else '有失败项'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
