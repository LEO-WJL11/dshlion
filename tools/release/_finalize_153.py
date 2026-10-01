#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""出 1.5.3 的四个包（1.5.2 → 1.5.3）。

【1.5.3 改了什么】
  · 上下文窗口默认 16K（省预填充），Agent 能用 context_window 工具自己调大、干完调回来；
  · Agent 能用 context_prune 工具真删掉前面没用的历史（保留最近几条）；
  · 改动人工审核：改文件的工具先攒成待审改动，人工点通过才落盘，打回则按理由重写；
  · VS Code 插件：右侧栏（secondary sidebar）直接是 Agent 面板，工作区=打开的文件夹，
    @ 引用单文件进上下文，面板里就能通过/打回改动。

【和 152 版脚本的区别】这一版把"版本号"做成参数，省得每出一版就复制一份脚本、
到处漏改版本号（1.5.1→1.5.2 时就是复制出来的，改一个漏一个）。
"""

import glob
import hashlib
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = '1.5.3'
ISCC = r'C:\Users\Leo\is6573\ISCC.exe'
REL = os.path.join(ROOT, 'installer', 'release')
DIST = os.path.join(ROOT, 'dist')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def run(args, cwd=None, timeout=3600):
    print('$ %s' % ' '.join(str(a) for a in args))
    r = subprocess.run(args, cwd=cwd or ROOT, capture_output=True, text=True,
                       errors='replace', timeout=timeout)
    if r.returncode != 0:
        print(((r.stdout or '') + (r.stderr or ''))[-2500:])
        raise SystemExit('命令失败（exit=%d）' % r.returncode)
    return r


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for c in iter(lambda: f.read(1 << 20), b''):
            h.update(c)
    return h.hexdigest().upper()


def stage_dist(jar):
    print('1.5 暂存 dist（jar / 运行时 / 技能）')
    shutil.copy2(jar, os.path.join(DIST, os.path.basename(jar)))
    installed = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs', 'LionBox')
    for name in ('runtime-jre', 'runtime-vulkan'):
        dst = os.path.join(DIST, name)
        if os.path.isdir(dst) and os.listdir(dst):
            print('   dist\\%s 已在' % name)
            continue
        src = os.path.join(installed, name)
        if not os.path.isdir(src):
            raise SystemExit('dist\\%s 不在，已装目录也没有：%s' % (name, src))
        shutil.copytree(src, dst)
        print('   %s → dist\\%s' % (src, name))
    sk_src = os.path.join(ROOT, 'skills')
    sk_dst = os.path.join(DIST, 'skills')
    if os.path.isdir(sk_src):
        shutil.rmtree(sk_dst, ignore_errors=True)
        shutil.copytree(sk_src, sk_dst)
        print('   skills → dist\\skills')


def main():
    skip_jar = '--no-jar' in sys.argv

    if not skip_jar:
        print('1. 编后端 jar')
        run([sys.executable, os.path.join('tools', 'dev', '_mvn.py'), '-o', '-q',
             '-DskipTests', 'package'])
    jar = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
    if not os.path.isfile(jar):
        raise SystemExit('没找到 fat jar：%s' % jar)
    if os.path.getsize(jar) < 20 * 1024 * 1024:
        raise SystemExit('jar 只有 %.1f MB —— 这是瘦 jar（fat jar 约 37MB），'
                         '八成打包时有进程占着它，重跑一次' % (os.path.getsize(jar) / 1048576.0))
    print('   jar %s 字节' % format(os.path.getsize(jar), ','))
    stage_dist(jar)

    print('2. 主安装包（ISCC）')
    run([ISCC, os.path.join('installer', 'LionBox.iss')])
    main_exe = os.path.join(REL, 'LionBox-Setup-%s.exe' % VER)
    if not os.path.isfile(main_exe):
        raise SystemExit('没出主安装包：%s' % main_exe)

    print('3. 桌面版（Tauri exe + ISCC）')
    tauri_exe = os.path.join(ROOT, 'desktop', 'tauri', 'src-tauri', 'target', 'release',
                             'lionbox-desktop.exe')
    if not os.path.isfile(tauri_exe):
        raise SystemExit('Tauri 壳还没编：cd desktop/tauri && npx tauri build --no-bundle')
    run([ISCC, os.path.join('installer', 'LionBoxDesktop.iss')])

    print('4. VS Code 插件（zip 口径打 vsix，排除构建产物）')
    import zipfile
    vsix_src = os.path.join(ROOT, 'extensions', 'vscode')
    vsix = os.path.join(REL, 'LionBox-VSCode-%s.vsix' % VER)
    if os.path.isfile(vsix):
        os.remove(vsix)
    skip_dirs = {'node_modules', '.vscode', 'out', '.git'}
    with zipfile.ZipFile(vsix, 'w', zipfile.ZIP_DEFLATED) as z:
        for base, dirs, files in os.walk(vsix_src):
            dirs[:] = [d for d in dirs if d not in skip_dirs]
            for f in files:
                if f.endswith(('.vsix', '.zip')):
                    continue                     # 别把上一次的包打进来（1.5.2 踩过）
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, vsix_src).replace('\\', '/'))

    print('5. JetBrains 插件（改版本号，class 不动）')
    run([sys.executable, os.path.join('tools', 'release', '_patch_jetbrains_version.py'),
         '--from', '1.5.2', '--to', VER])

    print('6. 删掉旧版本产物')
    for f in glob.glob(os.path.join(REL, '*.exe')) + glob.glob(os.path.join(REL, '*.vsix')) \
            + glob.glob(os.path.join(REL, '*.zip')):
        name = os.path.basename(f)
        if VER not in name:
            os.remove(f)
            print('   删掉 %s' % name)

    print()
    print('四个包（1.5.3）：')
    for f in sorted(glob.glob(os.path.join(REL, '*'))):
        n = os.path.getsize(f)
        print('   %-46s %8.2f MB  %s' % (os.path.basename(f), n / 1048576.0, sha256(f)[:16]))


if __name__ == '__main__':
    main()
