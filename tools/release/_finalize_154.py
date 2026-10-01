#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""出 1.5.4：**只有一个包** —— LionBox 本体 + VS Code 插件打在一起。

用户的话："就把它跟 VS Code 的打成一个包，然后原来其他的版本都不要了。"

所以这一版：
  · 交付物从四个减到**一个**：LionBox-Setup-x.y.z.exe 里含后端 + WebUI + 运行时 + 技能 + VS Code 插件；
  · 装完自动调 `code --install-extension` 把插件装进 VS Code（找不到 code 就留一句说明，不失败）；
  · 桌面版（Tauri 套壳）和 JetBrains 插件这两个"版本"不再出包，源码也从仓库里删掉
    （需要时 git 历史里还在）。

顺序很讲究：**先打 vsix，再打安装包** —— 安装脚本要把它打进去
（[Files] 里引用 release\\LionBox-VSCode-{#AppVersion}.vsix）。
"""

import glob
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = '1.5.4'
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


def main():
    # ---- 1) 后端 jar ----
    print('1. 编后端 jar')
    run([sys.executable, os.path.join('tools', 'dev', '_mvn.py'), '-o', '-q',
         '-DskipTests', 'package'])
    jar = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
    if not os.path.isfile(jar) or os.path.getsize(jar) < 20 * 1024 * 1024:
        raise SystemExit('jar 不对（瘦 jar 或不存在）：%s' % jar)
    print('   jar %s 字节' % format(os.path.getsize(jar), ','))

    # ---- 2) 暂存 dist（jar / 运行时 / 技能）----
    print('2. 暂存 dist')
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
    sk_src = os.path.join(ROOT, 'skills')
    if os.path.isdir(sk_src):
        shutil.rmtree(os.path.join(DIST, 'skills'), ignore_errors=True)
        shutil.copytree(sk_src, os.path.join(DIST, 'skills'))
    print('   jar / 运行时 / 技能 就位')

    # ---- 3) 先打 VS Code 插件（安装包要把它装进去）----
    print('3. 打 VS Code 插件（vsix）')
    vsix = os.path.join(REL, 'LionBox-VSCode-%s.vsix' % VER)
    # 【不能"把目录 zip 一下"】VS Code 认的 vsix 必须把扩展放在 extension/ 下、带
    # extension.vsixmanifest 和 [Content_Types].xml，否则装的时候报
    # "extension/package.json not found inside zip"（1.5.2~1.5.4 手打的那几版都是错的，
    # 是这一版真的调 code --install-extension 装了一遍才暴露出来）。
    run([sys.executable, os.path.join('tools', 'release', '_build_vsix.py'), VER])
    if not os.path.isfile(vsix):
        raise SystemExit('vsix 没打出来：%s' % vsix)
    print('   %s（%s 字节）' % (os.path.basename(vsix), format(os.path.getsize(vsix), ',')))

    # ---- 4) 一个包：ISCC ----
    print('4. 出安装包（含 VS Code 插件）')
    run([ISCC, os.path.join('installer', 'LionBox.iss')])
    setup = os.path.join(REL, 'LionBox-Setup-%s.exe' % VER)
    if not os.path.isfile(setup):
        raise SystemExit('没出安装包：%s' % setup)

    # ---- 5) 旧产物清掉（"其他版本不要了"）----
    print('5. 清掉旧产物与已废弃的版本')
    for f in glob.glob(os.path.join(REL, '*')):
        name = os.path.basename(f)
        if VER not in name:
            os.remove(f)
            print('   删掉 %s' % name)
    # 独立的 vsix 只是"打安装包时的输入"，它已经在安装包里了 —— 交付物就一个 exe，不能留两份
    for f in glob.glob(os.path.join(REL, '*.vsix')):
        os.remove(f)
        print('   删掉 %s（已在安装包内，不再单独发布）' % os.path.basename(f))
    # 桌面版 / JetBrains 插件：连源码一起删（需要时 git 历史里还在）
    for path in ('desktop/tauri', 'extensions/jetbrains', 'installer/LionBoxDesktop.iss'):
        p = os.path.join(ROOT, path.replace('/', os.sep))
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
            print('   删掉目录 %s' % path)
        elif os.path.isfile(p):
            os.remove(p)
            print('   删掉文件 %s' % path)

    print()
    n = os.path.getsize(setup)
    print('一个包：')
    print('   %-40s %8.2f MB' % (os.path.basename(setup), n / 1048576.0))
    print('   sha256 %s' % sha256(setup))
    print('   （内含 VS Code 插件 %s，安装时自动装进 VS Code）'
          % os.path.basename(vsix))


if __name__ == '__main__':
    main()
