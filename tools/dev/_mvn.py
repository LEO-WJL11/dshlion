#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""串行化 Maven 构建 —— 多智能体同时改代码时必须走这里，别直接敲 mvn。

为什么需要它：
  1) Maven 的 target/ 是**共享目录**，两个构建同时跑会互相删 class、报一堆莫名其妙的错；
     这里用一个原子目录锁（mkdir 是原子的）把构建排成队。
  2) PATH 里的 `mvn` 是 npm 的 maven-cli 假货：它不报错，但什么都不干。真 Maven 在
     Windows 上一般是 C:\\Users\\<用户>\\.maven\\apache-maven-<版本>\\bin\\mvn.cmd，
     下面按候选列表自动找，找到哪个用哪个。
  3) 这是 .py 而不是 .ps1：本机 PowerShell 执行策略禁止运行未签名 .ps1，
     `& xxx.ps1` 直接 SecurityError；Python 没有这个坑。

用法（参数原样透传给 mvn）：
    python tools/dev/_mvn.py -o -q -DskipTests compile
    python tools/dev/_mvn.py -o -T 16 -DskipTests clean package

成功 exit 0；失败把 maven 的退出码原样返回，并打印最后几行便于定位。
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 控制台是 GBK 时中文日志会变乱码，这里强制 UTF-8（失败也不影响构建）
try:
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

# 候选路径：先看环境变量，再看常见安装位置，最后退化成 PATH 里的 mvn（可能是假货，会警告）
CANDIDATES = [
    os.environ.get('LIONBOX_MVN'),
    os.path.join(os.path.expanduser('~'), '.maven', 'apache-maven-3.9.10', 'bin', 'mvn.cmd'),
    r'C:\Users\Leo\.maven\apache-maven-3.9.10\bin\mvn.cmd',
]


def find_mvn():
    for c in CANDIDATES:
        if c and os.path.isfile(c):
            return c
    found = shutil.which('mvn')
    if found:
        print('[mvn] 警告：没找到真 Maven，退化成 PATH 里的 %s（这台机器上它是 npm 假货，多半什么都不干）' % found)
        return found
    raise SystemExit('[mvn] 找不到 Maven，请设置环境变量 LIONBOX_MVN 指向真的 mvn.cmd')


LOCK_DIR = os.path.join(tempfile.gettempdir(), 'lionbox-mvn.lock')
STALE_SECONDS = 20 * 60      # 超过 20 分钟的锁当成上一个构建崩了留下的死锁
WAIT_SECONDS = 30 * 60       # 等锁最多等 30 分钟


def acquire_lock():
    deadline = time.time() + WAIT_SECONDS
    while True:
        try:
            os.mkdir(LOCK_DIR)          # 原子操作：抢到就拿到锁
            with open(os.path.join(LOCK_DIR, 'owner.txt'), 'w', encoding='utf-8') as f:
                f.write('pid=%d\nargs=%s\n' % (os.getpid(), ' '.join(sys.argv[1:])))
            return
        except FileExistsError:
            try:
                age = time.time() - os.path.getmtime(LOCK_DIR)
                if age > STALE_SECONDS:
                    print('[mvn-lock] 发现 %.0f 分钟的旧锁，接管' % (age / 60))
                    shutil.rmtree(LOCK_DIR, ignore_errors=True)
                    continue
            except OSError:
                pass
            if time.time() > deadline:
                raise SystemExit('[mvn-lock] 等锁超过 %d 分钟，放弃（锁目录 %s）' % (WAIT_SECONDS // 60, LOCK_DIR))
            print('[mvn-lock] 有别的构建在跑，等 3 秒…')
            time.sleep(3)


def release_lock():
    shutil.rmtree(LOCK_DIR, ignore_errors=True)


def main():
    args = sys.argv[1:]
    if not args:
        raise SystemExit('用法：python tools/dev/_mvn.py -o -q -DskipTests compile')

    mvn = find_mvn()
    if not os.path.isfile(os.path.join(ROOT, 'pom.xml')):
        raise SystemExit('[mvn] %s 下没有 pom.xml，别在错误目录里构建' % ROOT)

    acquire_lock()
    try:
        print('[mvn-lock] 拿到锁：mvn %s' % ' '.join(args))
        t0 = time.time()
        try:
            code = subprocess.call([mvn] + args, cwd=ROOT)
        except OSError:
            # 少数环境下 CreateProcess 不认 .cmd，退化成 shell 调用
            code = subprocess.call('"%s" %s' % (mvn, ' '.join(args)), cwd=ROOT, shell=True)
        print('[mvn-lock] 构建结束：exit=%d，耗时 %.1f 秒' % (code, time.time() - t0))
        return code
    finally:
        release_lock()


if __name__ == '__main__':
    sys.exit(main())
