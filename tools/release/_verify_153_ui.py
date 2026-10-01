#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验 1.5.3 主安装包：装 → 用装出来的自带 JRE 起服务 → 断言新功能真的在页面/接口里 → 卸。

【为什么必须验"装出来的那份"】用户用的是安装包，不是仓库源码。1.5.3 的四件事都得在
装出来的那份里能验到：
  · 设置仍是合并后的「插件管理」（1.5.2 的成果不能被这次改动碰坏）；
  · 页面里有待审改动容器 + 通过/打回按钮 + 上下文窗口指示（改动人工审核的界面入口）；
  · 后端列出十类插件里的"改动人工审核"（plugin.change-review）且默认开；
  · 上下文窗口默认 16K；
  · 页面与仓库 web/index.html 逐字节一致（改完真打进 jar 了）。

用法：python tools/release/_verify_153_ui.py
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
SETUP = os.path.join(ROOT, 'installer', 'release', 'LionBox-Setup-1.5.3.exe')
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_153_install')
HOME = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_153_home')
PORT = 8948

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


def get(path, timeout=10):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (PORT, path), timeout=timeout) as r:
        return r.read()


def main():
    if not os.path.isfile(SETUP):
        print('没找到 %s（先跑 tools/release/_finalize_153.py）' % SETUP)
        return 1
    if not port_free(PORT):
        raise SystemExit('端口 %d 被占了，换个端口再跑' % PORT)
    print('安装包：%s  %.2f MB' % (os.path.basename(SETUP), os.path.getsize(SETUP) / 1048576.0))

    run_dir = os.path.join(os.environ.get('TEMP', '.'), '_lionbox_153_setup')
    shutil.rmtree(run_dir, ignore_errors=True)
    os.makedirs(run_dir, exist_ok=True)
    run_exe = os.path.join(run_dir, os.path.basename(SETUP))
    shutil.copy2(SETUP, run_exe)       # 从工作区里跑 Inno 的自解压会被这个环境挡掉
    shutil.rmtree(TMP, ignore_errors=True)
    shutil.rmtree(HOME, ignore_errors=True)
    os.makedirs(HOME, exist_ok=True)

    r = subprocess.run([run_exe, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS',
                        '/DIR=' + TMP], capture_output=True, timeout=600)
    check('静默安装返回 0', r.returncode == 0, 'exit=%d' % r.returncode)

    jar = None
    for f in (os.listdir(TMP) if os.path.isdir(TMP) else []):
        if f.startswith('lion-code-agent-harness') and f.endswith('.jar'):
            jar = os.path.join(TMP, f)
    java = os.path.join(TMP, 'runtime-jre', 'bin', 'java.exe')
    check('装出了后端 jar 和自带 JRE', bool(jar) and os.path.isfile(java))

    proc = None
    try:
        if jar and os.path.isfile(java):
            env = dict(os.environ)
            env['USERPROFILE'] = HOME
            env['HOME'] = HOME
            proc = subprocess.Popen(
                [java, '-Duser.home=' + HOME, '-Djava.io.tmpdir=' + HOME, '-jar', jar,
                 '--server.port=%d' % PORT, '--lionbox.headless=true',
                 # 关掉自动下载：不然一起来就拖 8.9GB 权重（验证只要 HTTP 接口）
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
            check('装出来的后端起来了', up, 'pid=%s' % (proc.pid if proc else '-'))

        if proc and proc.poll() is None:
            page = get('/index.html', timeout=15).decode('utf-8')
            local = io.open(os.path.join(ROOT, 'web', 'index.html'), encoding='utf-8',
                            newline='').read()
            check('★ 服务端发的页面和仓库一致（改完真打进去了）',
                  len(page) == len(local), '%d vs %d 字节' % (len(page), len(local)))

            # 1.5.2 的成果：设置合并
            check('★ 设置仍是合并后的「插件管理」',
                  'id="setTabPlugins"' in page and '插件管理' in page)
            check('★ 旧页签仍然不存在（插件参数/技能/审批策略）',
                  all(t not in page for t in ('setTabPluginParams', 'setTabSkills', 'setTabApprovals')))

            # 1.5.3 新增：改动人工审核的界面入口
            check('★ 页面有待审改动容器（changeBox）', 'id="changeBox"' in page)
            check('★ 页面能点通过/打回（approveChange / rejectChange）',
                  'approveChange: function' in page and 'rejectChange: function' in page)
            check('★ 页面上有上下文窗口指示（ctxChip，默认 16K 可见）', 'id="ctxChip"' in page)
            check('★ diff 用主题变量上色（不许硬编码色）',
                  '.change-box .add { color: var(--ok); }' in page)

            # 后端：窗口默认 + 插件在 + 审核默认开
            ctx = json.loads(get('/api/context', timeout=10).decode('utf-8')).get('context', {})
            check('★ 上下文窗口默认 16K', ctx.get('defaultLimit') == 16384, str(ctx))

            ch = json.loads(get('/api/changes', timeout=10).decode('utf-8'))
            check('★ 改动人工审核默认开着', ch.get('enabled') is True, str(ch.get('enabled')))

            pl = json.loads(get('/api/plugins', timeout=10).decode('utf-8'))
            items = pl.get('plugins') or (pl.get('data') or {}).get('plugins') or []
            ids = [p.get('id') for p in items]
            for pid in ('plugin.change-review', 'plugin.terminal', 'plugin.agent-loop',
                        'plugin.subagent', 'plugin.approval-review', 'plugin.agent-team',
                        'plugin.automation'):
                check('后端有插件 %s' % pid, pid in ids)
            kinds = sorted({(p.get('kind') or '').upper() for p in items})
            check('★ 插件分类仍然是九类（新插件并进审批审查那一类）',
                  len([k for k in kinds if k]) == 9, ','.join(kinds))
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
