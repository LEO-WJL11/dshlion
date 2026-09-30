# -*- coding: utf-8 -*-
r"""最终口径：只算 git 跟踪的文件，并把硬件/EDA 研究草稿单独剔出去。"""
import io
import os
import subprocess

ROOT = r'C:\Users\Leo\Desktop\lion-code'
out = subprocess.run(['git', '-c', 'core.quotepath=false', 'ls-files'], cwd=ROOT,
                     capture_output=True, text=True,
                     encoding='utf-8', errors='replace').stdout.splitlines()
print('git 跟踪的文件总数：%d' % len(out))

JUNK = ('_eda_tools/', '_hwres/', '_research/', 'usbresearch/', 'output/',
        '7840u.html', 'v3000.html', '_mrf.txt', '_rk3588usb.txt')
BINARY_EXT = {'.exe', '.dll', '.jar', '.gguf', '.zip', '.7z', '.png', '.ico', '.jpg',
              '.pdf', '.rar', '.brd', '.rom', '.sym', '.fz', '.dat', '.net', '.class',
              '.part', '.bin'}

buckets = {}
junk = [0, 0]
for rel in out:
    p = os.path.join(ROOT, rel.replace('/', os.sep))
    ext = os.path.splitext(rel)[1].lower()
    is_junk = rel.startswith(JUNK) or ext in BINARY_EXT
    if is_junk:
        junk[0] += 1
        try:
            junk[1] += os.path.getsize(p)
        except Exception:
            pass
        continue
    try:
        n = len(io.open(p, encoding='utf-8', errors='replace').read().splitlines())
    except Exception:
        n = 0
    if rel.startswith('src/main/java'):
        k = 'Java 主代码'
    elif rel.startswith('src/main/resources'):
        k = '配置与提示词（resources）'
    elif rel.startswith('web/'):
        k = '界面 web/index.html'
    elif rel.startswith('installer/'):
        k = '安装包脚本'
    elif rel.startswith('docs/') or ext == '.md':
        k = '文档'
    elif os.path.basename(rel).startswith('_check_'):
        k = '自测套件（_check_*.py）'
    elif os.path.basename(rel).startswith('_'):
        k = '构建/收尾/补丁脚本'
    elif rel.startswith('dist/'):
        k = '随包脚本与说明（dist）'
    else:
        k = '其它（配置/脚本）'
    b = buckets.setdefault(k, [0, 0])
    b[0] += 1
    b[1] += n

print()
print('%-28s %6s %10s' % ('区域', '文件', '行数'))
print('-' * 48)
tf = tl = 0
for k in sorted(buckets, key=lambda x: -buckets[x][1]):
    f, n = buckets[k]
    tf += f
    tl += n
    print('%-28s %6d %10s' % (k, f, format(n, ',')))
print('-' * 48)
print('%-28s %6d %10s' % ('合计', tf, format(tl, ',')))
print()
prod = sum(buckets[k][1] for k in ('Java 主代码', '配置与提示词（resources）',
                                  '界面 web/index.html', '安装包脚本'))
print('其中"产品本身"（Java + 配置/提示词 + 界面 + 安装脚本）：%s 行' % format(prod, ','))
print('测试与构建工具：%s 行；文档：%s 行'
      % (format(sum(buckets[k][1] for k in ('自测套件（_check_*.py）', '构建/收尾/补丁脚本',
                                            '随包脚本与说明（dist）')), ','),
         format(sum(buckets[k][1] for k in ('文档',)), ',')))
print()
print('不算代码的（硬件/EDA 研究草稿、二进制、权重、jre）：%d 个文件 / %.0f MB'
      % (junk[0], junk[1] / 1048576.0))
