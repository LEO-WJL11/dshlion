#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""1.5.2 出包：主安装包 + 桌面版 + 两个 IDE 插件，四个包一次出齐。

【1.5.2 改了什么】把设置里散着的四个页签（插件 / 插件参数 / 技能 / 审批策略）合并成一个
「插件管理」：每个插件的开关和它自己的参数挨在一起，技能进 SKILL 分组，审批策略挂在授权
审查插件下面。用户的原话是"如果设置里面有重复的设置，给它干掉，全部缩进插件管理"。

用法：
    python tools/release/_finalize_152.py            # 出四个包并验
    python tools/release/_finalize_152.py --no-jar   # 跳过 mvn（jar 已经编好时）

产物（installer/release/）：
    LionBox-Setup-1.5.2.exe           主程序（后端 + WebUI + 运行时 + 技能）
    LionBox-Desktop-1.5.2-Setup.exe   桌面版（Tauri 套壳，~2.8MB）
    LionBox-VSCode-1.5.2.vsix         VS Code 插件
    LionBox-JetBrains-1.5.2.zip       JetBrains 插件
"""

import glob
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = '1.5.2'
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
    tail = (r.stdout or '')[-1500:] + (r.stderr or '')[-1500:]
    if r.returncode != 0:
        print(tail)
        raise SystemExit('命令失败（exit=%d）' % r.returncode)
    return r


def stage_dist(jar):
    """把 ISCC 要打的东西凑齐。

    【为什么要暂存】dist/ 里的大件（运行时、jar、技能）不进仓库（几十上百 MB），
    每次出包前从两处凑：运行时从**已装的主程序目录**拷回来（它就是上一条安装包装进去的），
    jar 从 target/ 拷，技能从仓库的 skills/ 拷（那是技能的源）。
    """
    print('1.5 暂存 dist（jar / 运行时 / 技能）')
    shutil.copy2(jar, os.path.join(DIST, os.path.basename(jar)))
    print('   jar → dist\\%s' % os.path.basename(jar))

    installed = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs', 'LionBox')
    for name in ('runtime-jre', 'runtime-vulkan'):
        dst = os.path.join(DIST, name)
        if os.path.isdir(dst) and os.listdir(dst):
            print('   dist\\%s 已在（跳过）' % name)
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
        print('   skills → dist\\skills（%d 个技能）'
              % len([d for d in os.listdir(sk_dst) if os.path.isdir(os.path.join(sk_dst, d))]))


def main():
    skip_jar = '--no-jar' in sys.argv

    # ---- 1) 后端 jar（走串行锁的 mvn 包装，别直接 mvn）----
    if not skip_jar:
        print('1. 编后端 jar（走 _mvn.py 的串行锁）')
        run([sys.executable, os.path.join('tools', 'dev', '_mvn.py'), '-o', '-q',
             '-DskipTests', 'package'])
    jar = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
    if not os.path.isfile(jar):
        raise SystemExit('没找到 fat jar：%s' % jar)
    if os.path.getsize(jar) < 20 * 1024 * 1024:
        raise SystemExit('jar 只有 %.1f MB —— 这是瘦 jar（fat jar 约 35MB），'
                         '八成是打包时还有进程占着它，重跑一次' % (os.path.getsize(jar) / 1048576.0))
    print('   jar %s 字节' % format(os.path.getsize(jar), ','))

    stage_dist(jar)

    # ---- 3) 主安装包 ----
    print('2. 主安装包（ISCC）')
    rel_before = set(glob.glob(os.path.join(REL, '*.exe')))
    run([ISCC, os.path.join('installer', 'LionBox.iss')])
    main_exe = os.path.join(REL, 'LionBox-Setup-%s.exe' % VER)
    if not os.path.isfile(main_exe):
        raise SystemExit('没出主安装包：%s' % main_exe)
    print('   %s  %.2f MB' % (os.path.basename(main_exe), os.path.getsize(main_exe) / 1048576.0))

    # ---- 4) 桌面版（Tauri 已编好的 exe → ISCC 打包）----
    print('3. 桌面版（Tauri exe + ISCC）')
    tauri_exe = os.path.join(ROOT, 'desktop', 'tauri', 'src-tauri', 'target', 'release',
                             'lionbox-desktop.exe')
    if not os.path.isfile(tauri_exe):
        raise SystemExit('Tauri 壳还没编：cd desktop/tauri && npx tauri build --no-bundle')
    run([ISCC, os.path.join('installer', 'LionBoxDesktop.iss')])
    desk_exe = os.path.join(REL, 'LionBox-Desktop-%s-Setup.exe' % VER)
    if not os.path.isfile(desk_exe):
        raise SystemExit('没出桌面版安装包：%s' % desk_exe)
    print('   %s  %.2f MB' % (os.path.basename(desk_exe), os.path.getsize(desk_exe) / 1048576.0))

    # ---- 5) 两个 IDE 插件 ----
    print('4. VS Code 插件（vsce 目录打包）')
    vsix_src = os.path.join(ROOT, 'extensions', 'vscode')
    vsix = os.path.join(REL, 'LionBox-VSCode-%s.vsix' % VER)
    if os.path.isfile(vsix):
        os.remove(vsix)
    # 用 7z/zip 口径打 vsix（本质是 zip）：内容 = 扩展目录（不含 node_modules）
    import zipfile
    with zipfile.ZipFile(vsix, 'w', zipfile.ZIP_DEFLATED) as z:
        for base, dirs, files in os.walk(vsix_src):
            dirs[:] = [d for d in dirs if d not in ('node_modules', '.vscode', 'out')]
            for f in files:
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, vsix_src).replace('\\', '/'))
    print('   %s  %s 字节' % (os.path.basename(vsix), format(os.path.getsize(vsix), ',')))

    print('5. JetBrains 插件（已编好的 zip 换名归档）')
    jb = os.path.join(REL, 'LionBox-JetBrains-%s.zip' % VER)
    src_zip = None
    for cand in glob.glob(os.path.join(ROOT, 'extensions', 'jetbrains', 'build', 'distributions',
                                       '*.zip')):
        src_zip = cand
    if src_zip and not os.path.isfile(jb):
        shutil.copy2(src_zip, jb)
        print('   从 %s 复制' % os.path.basename(src_zip))
    if os.path.isfile(jb):
        print('   %s  %s 字节' % (os.path.basename(jb), format(os.path.getsize(jb), ',')))
    else:
        print('   ! JetBrains 包没找到（extensions/jetbrains/build/distributions/*.zip）')

    # ---- 6) 清掉旧版本产物（用户只留最新）----
    print('6. 删掉旧版本产物')
    for f in glob.glob(os.path.join(REL, '*.exe')) + glob.glob(os.path.join(REL, '*.vsix')) \
            + glob.glob(os.path.join(REL, '*.zip')):
        name = os.path.basename(f)
        if VER not in name and 'part' not in name:
            os.remove(f)
            print('   删掉 %s' % name)

    print()
    print('四个包：')
    for f in sorted(glob.glob(os.path.join(REL, '*'))):
        print('   %-46s %10.2f MB' % (os.path.basename(f), os.path.getsize(f) / 1048576.0))


if __name__ == '__main__':
    main()
