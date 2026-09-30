# -*- coding: utf-8 -*-
r"""推完之后核对两个远端里**实际**是什么（不信本地，信远端）。

主体走 git fetch（api.github.com 在这台机器上老是掐连接），私有仓库走 ls-remote。
核对点：
  1. 公开仓库的 installer/release 里**只有**当前这一版的 exe
  2. 远端那个 exe 和本地这份是同一个文件（blob 一致）+ 体积一致
  3. 公开仓库的 web/index.html、PersistentShell.java、AbstractToolPlugin.java 是这一版
  4. 交付清单里写的就是这一版的包名/体积/sha256，且没有残留旧包名
  5. 安装脚本版本号 = 这一版；README 里说明了模式工具集与常驻终端
  6. 公开仓库里 grep 不到"盒子"（本轮就是把盒子文案清掉）
  7. 私有仓库 origin/master = 本地 HEAD，且两边的 exe blob 一致
"""
import hashlib
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
ROOT = r'C:\Users\Leo\Desktop\lion-code'
VER = '1.4.2'
OLD = '1.4.1'
EXE = 'installer/release/LionBox-Setup-%s.exe' % VER
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


def git(*args):
    # 不指定编码时 Windows 上按 GBK 读 git 输出，带中文的文档会 UnicodeDecodeError
    p = subprocess.run(['git', '-c', 'core.quotepath=false'] + list(args), cwd=ROOT,
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.stdout.strip()


def local_blob(path):
    return git('hash-object', path)


exe_local = os.path.join(ROOT, EXE.replace('/', os.sep))
blob = open(exe_local, 'rb').read()
size, sha = len(blob), hashlib.sha256(blob).hexdigest().upper()
local_head = git('rev-parse', 'HEAD')
print('  本地 HEAD %s' % local_head[:12])
print('  本地包 %s  %s 字节  sha256=%s' % (os.path.basename(EXE), format(size, ','), sha))

# ---------- 公开仓库（dshlion/main）----------
try:
    git('fetch', 'dshlion', 'main')
    fetched = git('rev-parse', 'FETCH_HEAD')
    check('★ 公开仓库 main 就是刚推的那条提交',
          fetched == git('rev-parse', 'dshlion/main'),
          '%s vs %s' % (fetched[:12], git('rev-parse', 'dshlion/main')[:12]))

    ls = [l for l in git('ls-tree', '-r', '--long', 'FETCH_HEAD', 'installer/release').splitlines()
          if l.strip()]
    names = sorted(l.split()[-1].split('/')[-1] for l in ls)
    check('★ 公开仓库里只留当前这一版的安装包', names == ['LionBox-Setup-%s.exe' % VER], str(names))
    check('★ 旧版安装包已经删掉（%s 不在远端）' % OLD, 'LionBox-Setup-%s.exe' % OLD not in names)
    remote_exe_blob = ls[0].split()[2] if ls else ''
    remote_exe_size = ls[0].split()[3] if ls else ''
    check('★ 远端那个包和本地这份是同一个文件（git blob 一致）',
          remote_exe_blob == local_blob(EXE),
          '%s vs %s' % (remote_exe_blob[:12], local_blob(EXE)[:12]))
    check('★ 远端体积 = 本地体积', remote_exe_size == str(size),
          '%s vs %s' % (remote_exe_size, size))

    for path, label in (('web/index.html', '界面文件'),
                        ('src/main/java/com/lioncode/core/plugin/tool/shell/PersistentShell.java',
                         '常驻终端源码'),
                        ('src/main/java/com/lioncode/core/plugin/tool/AbstractToolPlugin.java',
                         '参数转换源码'),
                        ('src/main/java/com/lioncode/web/controller/ProviderController.java',
                         '文案改过的控制器')):
        remote = git('rev-parse', 'FETCH_HEAD:%s' % path)
        check('★ 公开仓库的%s也是这一版（blob 一致）' % label, remote == local_blob(path),
              '%s vs %s' % (remote[:12], local_blob(path)[:12]))

    def remote_text(path):
        return git('show', 'FETCH_HEAD:%s' % path)

    checklist = remote_text('docs/交付清单.md')
    check('★ 公开仓库的交付清单里写的是这一版的包名/体积/sha256',
          ('LionBox-Setup-%s.exe' % VER) in checklist
          and format(size, ',') in checklist and sha in checklist,
          '名字=%s 体积=%s 哈希=%s' % (('LionBox-Setup-%s.exe' % VER) in checklist,
                                       format(size, ',') in checklist, sha in checklist))
    check('★ 交付清单里没有残留旧包名', 'LionBox-Setup-%s.exe' % OLD not in checklist)

    iss = remote_text('installer/LionBox.iss')
    check('★ 公开仓库的安装脚本版本号是 %s' % VER, '#define AppVersion     "%s"' % VER in iss)

    readme = remote_text('README.md')
    check('★ 公开仓库的 README 说明了模式工具集和常驻终端',
          '极简模式' in readme and '持续运行的终端' in readme)

    # 本轮的核心：**产品本身**（源码 + 界面 + 随包文档）里不该再有"盒子"字样。
    # 交付清单/自测记录/iss 注释里会提到"不做盒子了"这段历史（那是说明，不是残留文案），
    # 所以只扫 src/ web/ dist/ 这三处。
    box_hits = []
    for f in git('ls-tree', '-r', '--name-only', 'FETCH_HEAD').splitlines():
        if not (f.startswith('src/') or f.startswith('web/') or f.startswith('dist/')):
            continue
        if not f.lower().endswith(('.java', '.html', '.md', '.yml', '.js', '.bat', '.ps1')):
            continue
        try:
            if '盒子' in git('show', 'FETCH_HEAD:%s' % f):
                box_hits.append(f)
        except Exception:
            pass
    check('★ 公开仓库里产品代码/界面/随包文档 grep 不到"盒子"', not box_hits,
          '、'.join(box_hits[:6]))

    print('  远端 main 提交: %s  %s'
          % (fetched[:12], git('log', '-1', '--pretty=%s', 'FETCH_HEAD')))
except Exception as e:
    check('公开仓库核对（git fetch）', False, '%s: %s' % (type(e).__name__, e))

# ---------- 私有仓库（origin/master）----------
try:
    remote_head = git('ls-remote', 'origin', 'refs/heads/master').split()[0]
    check('★ 私有仓库 origin/master = 本地 HEAD', remote_head == local_head,
          '%s vs %s' % (remote_head[:12], local_head[:12]))
    ls = [l for l in git('ls-tree', '-r', '--long', 'origin/master', 'installer/release').splitlines()
          if l.strip()]
    check('★ 私有仓库里也只有当前这一版的安装包',
          len(ls) == 1 and ls[0].split()[-1].endswith('LionBox-Setup-%s.exe' % VER),
          ' / '.join(l.split()[-1] for l in ls))
    remote_exe_blob = ls[0].split()[2] if ls else ''
    check('★ 私有仓库的包和本地是同一个文件', remote_exe_blob == local_blob(EXE),
          '%s vs %s' % (remote_exe_blob[:12], local_blob(EXE)[:12]))
except Exception as e:
    check('私有仓库核对', False, '%s: %s' % (type(e).__name__, e))

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
