#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把桌面版安装包切成 <100MB 的分卷，好放进 GitHub 仓库。

【为什么要切】桌面版 178.79MB，GitHub 单文件硬上限 100MB，push 会直接被拒；
Release 附件没这个限制，但传 Release 需要 token，这台机器上没有。
切成分卷就能跟着仓库走，用户拿到两份 .part 拼一下就还原成原始 exe。

【怎么保证没拼错】拼完必须能和原始文件 sha256 对得上 —— 脚本会自己验一遍，
对不上就报错，不会留下一个"看起来拼好了其实是坏的"exe。

用法：python tools/release/_split_desktop.py            # 默认 90MB 一片
      python tools/release/_split_desktop.py --verify    # 只校验现有分卷能否还原
"""

import argparse
import glob
import hashlib
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join(ROOT, 'installer', 'release')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def sha256_of(path, chunk=1024 * 1024):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest().upper()


def find_target():
    cands = sorted(glob.glob(os.path.join(REL, 'LionBox-Desktop-*-Setup.exe')))
    if not cands:
        raise SystemExit('没找到桌面版安装包（installer/release/LionBox-Desktop-*-Setup.exe）')
    return cands[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--part-mb', type=int, default=90, help='每片多大（MB），必须 <100')
    ap.add_argument('--verify', action='store_true', help='只校验现有分卷能否还原')
    args = ap.parse_args()

    if args.part_mb >= 100:
        raise SystemExit('每片必须小于 100MB（GitHub 单文件硬上限）')

    exe = find_target()
    name = os.path.basename(exe)
    parts = sorted(glob.glob(exe + '.part*'))
    orig_sha = sha256_of(exe) if os.path.isfile(exe) else None
    print('目标：%s（%s 字节）' % (name, format(os.path.getsize(exe), ',') if orig_sha else '缺失'))
    if orig_sha:
        print('原始 sha256：%s' % orig_sha)

    if not args.verify:
        for p in parts:
            os.remove(p)
        size = os.path.getsize(exe)
        chunk = args.part_mb * 1024 * 1024
        n = 0
        with open(exe, 'rb') as src:
            while True:
                data = src.read(chunk)
                if not data:
                    break
                n += 1
                out = '%s.part%d' % (exe, n)
                with open(out, 'wb') as f:
                    f.write(data)
                print('  写出 %s（%s 字节）' % (os.path.basename(out), format(len(data), ',')))
        print('共 %d 片' % n)

    parts = sorted(glob.glob(exe + '.part*'))
    if not parts:
        raise SystemExit('没有分卷可校验')

    # 拼一遍到临时文件，和原始 exe 比 sha256
    tmp = exe + '.rejoined.tmp'
    h = hashlib.sha256()
    total = 0
    with open(tmp, 'wb') as out:
        for p in parts:
            with open(p, 'rb') as f:
                while True:
                    b = f.read(1024 * 1024)
                    if not b:
                        break
                    out.write(b)
                    h.update(b)
                    total += len(b)
    got = h.hexdigest().upper()
    ok = (orig_sha is None) or (got == orig_sha)
    print('分卷 %d 片，合计 %s 字节' % (len(parts), format(total, ',')))
    print('拼装后 sha256：%s  → %s' % (got, 'OK 与原始一致' if ok else 'FAIL 与原始不一致'))
    os.remove(tmp)
    if not ok:
        raise SystemExit(1)

    # 顺带把拼装用的批处理写出来（用户双击即可）
    bat = os.path.join(REL, '拼装桌面版.bat')
    ver = name.replace('LionBox-Desktop-', '').replace('-Setup.exe', '')
    with io.open(bat, 'w', encoding='gbk', newline='\r\n') as f:
        f.write('@echo off\r\n')
        f.write('chcp 936 >nul\r\n')
        f.write('rem 把 LionBox-Desktop-%s-Setup.exe 的分卷拼回完整安装包\r\n' % ver)
        f.write('setlocal\r\n')
        f.write('cd /d "%%~dp0"\r\n')
        f.write('set OUT=LionBox-Desktop-%s-Setup.exe\r\n' % ver)
        f.write('if exist "%%OUT%%" del "%%OUT%%"\r\n')
        cmd = 'copy /b '
        cmd += '+'.join('"LionBox-Desktop-%s-Setup.exe.part%d"' % (ver, i + 1)
                        for i in range(len(parts)))
        f.write('%s "%%OUT%%" >nul\r\n' % cmd)
        f.write('echo.\r\n')
        f.write('echo 已拼出 %%OUT%%\r\n')
        f.write('echo 校验：certutil -hashfile "%%OUT%%" SHA256\r\n')
        f.write('echo 期望：%s\r\n' % (orig_sha or '（见 安装包清单.md）'))
        f.write('pause\r\n')
    print('拼装脚本：%s' % bat)
    return 0


if __name__ == '__main__':
    sys.exit(main())
