# -*- coding: utf-8 -*-
r"""一键跑全部 _check_*.py 回归套件（串行，避免抢端口/CPU 造成假失败）。

用法：python _run_all_checks.py [套件名关键字...]
不给参数就跑全部；给了关键字就只跑文件名里含关键字的那些。
"""
import glob
import io
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
# 项目根目录（本脚本在 tools/checks/ 下，往上三层才是根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# 【别混】套件就在本脚本旁边（tools/checks/），cwd 才是项目根 ——
# 之前把这两件事都用 ROOT，挪进 tools/ 之后 glob 到 0 个套件、还"全部通过"。
HERE = os.path.dirname(os.path.abspath(__file__))

files = sorted(glob.glob(os.path.join(HERE, '_check_*.py')))
if len(sys.argv) > 1:
    keys = sys.argv[1:]
    files = [f for f in files if any(k in os.path.basename(f) for k in keys)]

results = []
t_all = time.time()
for f in files:
    name = os.path.basename(f)
    print('=' * 72)
    print('>>> ' + name)
    print('=' * 72)
    t0 = time.time()
    p = subprocess.run([sys.executable, f], cwd=ROOT, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    dt = time.time() - t0
    ok = (p.returncode == 0)
    results.append((name, ok, dt))
    lines = (p.stdout or '').splitlines()
    fails = [l for l in lines if '[FAIL]' in l]
    for l in fails:
        print('   ' + l.strip())
    if lines:
        print('   ' + lines[-1].strip())
    print('   -> %s  (%.1fs)' % ('通过' if ok else '失败', dt))
    if not ok and not fails:
        tail = (p.stderr or '').splitlines()[-15:]
        for l in tail:
            print('   stderr: ' + l)

print()
print('=' * 72)
print('汇总（共 %d 个套件，用时 %.1f 分钟）' % (len(results), (time.time() - t_all) / 60))
print('=' * 72)
bad = 0
for name, ok, dt in results:
    if not ok:
        bad += 1
    print('  %-34s %s  %5.1fs' % (name, '通过' if ok else '失败', dt))
print('失败 %d 个' % bad)
sys.exit(1 if bad else 0)
