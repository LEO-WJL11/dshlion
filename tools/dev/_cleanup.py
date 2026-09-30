# -*- coding: utf-8 -*-
r"""清理项目：只留**代码 + md + 安装包**，其余全删（调研资料挪到项目外，不销毁）。

用法：
    python _cleanup.py dry     # 只列出会动什么，不真动
    python _cleanup.py go      # 真删/真挪

判定规则：
 1. 后缀属于代码（.java .py .ps1 .sh .js .iss .xml .yml .yaml .html .bat .css .wav）
    → 留；但根目录下那些**历史一次性脚本**（_patch_/_finalize_11x-13x/_probe_/_msg_ 等）照样删。
 2. .md → 留。
 3. 安装包（installer/release/LionBox-Setup-*.exe）→ 留（只留当前这一版）。
 4. 其余（模型、PDF、构建产物、日志、旧包、调研数据…）→ 删；
    其中**没被 git 跟踪**的调研目录（删了就找不回来）改成"挪到项目外"。
"""
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
ROOT = r'C:\Users\Leo\Desktop\lion-code'
MOVED_OUT = r'C:\Users\Leo\Desktop\lion-code-清出来的资料'
GO = len(sys.argv) > 1 and sys.argv[1] == 'go'

CODE_EXT = {'.java', '.py', '.ps1', '.sh', '.js', '.iss', '.xml', '.yml', '.yaml',
            '.html', '.bat', '.cmd', '.css', '.wav'}
KEEP_MD = {'.md'}
KEEP_NAMES = {'LICENSE', '.gitignore'}

# 根目录下这些"历史上的一次性脚本/临时文件"不属于项目代码，删
JUNK_PATTERNS = [
    '_patch_', '_finalize_11', '_finalize_12', '_finalize_13', '_msg_', '_docs_',
    '_gen_1', '_bump_', '_verify_pkg_', '_verify_remote_12', '_verify_remote_13',
    '_verify_remote_122', '_fix_', '_probe_', '_mock_', '_one_native', '_test_',
    '_e2e_', '_restore_', '_repro_', '_scan_key', '_summarize_failures',
    '_tidy_selftest_doc', '_drive_lionbox', '_measure_', '_local_log_proxy',
    '_mimo_log_proxy', '_analyze_proxy_log', '_peek_', '_fake_llama', '_show_proxy',
    '_tool_categories', '_schema_dump', '_survey', '_deadcode', '_check_index',
    '_jarlist', '_regression', '_task_', '_sound_session', '_mimo_proxy',
    '_fc_', '_prompt_', '_bios_', '_x99_', '_mi899_', '_eprj', '_dig_phase2',
    '_me_ver', '_extract_bios', '_mrf', '_rsmojo', '_rk3588usb', '_bdti_',
    '_kics_', '_raspberry-pi', '_rp1-', '_rpiotg', '_RP-008362',
    'fetch', 'grepdl', 'v3000.html', '7840u.html', 'LionCode.exe', 'launcher.ps1',
]
# 这些是"当前在用的项目工具/脚本"，必须留（就算命中上面的关键字）
KEEP_EXACT = {
    '_build.ps1', '_sync_dshlion.sh', '_push_dshlion.sh', '_restart_dsh.ps1',
    '_count_loc.py', '_run_all_checks.py', '_finalize_140.py', '_verify_remote_140.py',
    '_probe_shell.py', '_probe_shell2.py', '_模型工程笔记.md',
}
# 整个目录：代码 + md 形态的调研资料，挪到项目外（删了就找不回来，所以先挪）
MOVE_DIRS = ['_research', '_hwres', 'usbresearch', 'dl', '_eda_tools', '_box_bios']
# 整个目录：构建产物 / 日志，直接删
DELETE_DIRS = ['target', 'output', 'logs', os.path.join('dist', 'logs'),
               os.path.join('dist', 'runtime-jre'), os.path.join('dist', 'runtime-vulkan'),
               '.idea', '_e2e_ws', '_fc_ws', os.path.join('installer', 'release', 'v1.0.0')]


def human(n):
    return '%.1f MB' % (n / 1024.0 / 1024.0) if n < 1024 ** 3 else '%.2f GB' % (n / 1024.0 ** 3)


def size_of(path):
    if os.path.isfile(path):
        return os.path.getsize(path)
    total = 0
    for dp, dn, fn in os.walk(path):
        for f in fn:
            try:
                total += os.path.getsize(os.path.join(dp, f))
            except OSError:
                pass
    return total


def tracked():
    p = subprocess.run(['git', 'ls-files'], cwd=ROOT, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    return set(p.stdout.splitlines())


TR = tracked()
deletes, moves, keeps = [], [], []

# ---- 目录 ----
for d in MOVE_DIRS:
    p = os.path.join(ROOT, d)
    if os.path.exists(p):
        moves.append(p)
for d in DELETE_DIRS:
    p = os.path.join(ROOT, d)
    if os.path.exists(p):
        deletes.append(p)

# ---- 根目录下的文件 ----
for name in sorted(os.listdir(ROOT)):
    p = os.path.join(ROOT, name)
    if os.path.isdir(p):
        continue
    ext = os.path.splitext(name)[1].lower()
    if name == 'LionBox-Setup-1.4.0.exe':
        keeps.append(p)
        continue
    if name in KEEP_EXACT:
        keeps.append(p)
        continue
    # 历史一次性脚本 / 临时文件
    if any(name.startswith(k) for k in JUNK_PATTERNS):
        deletes.append(p)
        continue
    if ext in KEEP_MD or name in KEEP_NAMES:
        keeps.append(p)
        continue
    if ext in CODE_EXT:
        keeps.append(p)
        continue
    deletes.append(p)          # 其余（.gguf / .pdf / .txt / .json / .log / .jar.bak…）

# ---- dist 里：留代码/md/脚本/批处理，删二进制 ----
dist = os.path.join(ROOT, 'dist')
if os.path.isdir(dist):
    for name in sorted(os.listdir(dist)):
        p = os.path.join(dist, name)
        if name in ('runtime-jre', 'runtime-vulkan', 'logs', '使用说明.md'):
            pass          # 已在上面处理（使用说明.md 会留在原地，不重复登记）
        if os.path.isdir(p):
            continue
        ext = os.path.splitext(name)[1].lower()
        if ext in ('.gguf', '.jar', '.exe', '.dll', '.bin', '.zip', '.log', '.pdf', '.png'):
            deletes.append(p)

# ---- 顶层 pdf 之类已经在上面的文件循环里处理；这里扫一遍全项目的大文件兜底 ----
BIG_EXT = {'.gguf', '.safetensors', '.rom', '.bin', '.zip', '.exe', '.jar', '.dll',
           '.iso', '.7z', '.rar', '.mp4', '.onnx'}
for dp, dn, fn in os.walk(ROOT):
    if '.git' in dp.split(os.sep):
        continue
    for f in fn:
        p = os.path.join(dp, f)
        if p in deletes or p in moves or p in keeps:
            continue
        ext = os.path.splitext(f)[1].lower()
        if f.endswith('.jar.bak-20260907-2230') or '.jar.bak' in f:
            deletes.append(p)
        elif ext in BIG_EXT and 'installer' + os.sep + 'release' + os.sep in p + os.sep:
            if 'LionBox-Setup-1.4.0.exe' not in f:
                deletes.append(p)
        elif ext in BIG_EXT and ext != '.jar':
            # 项目里的大二进制一律不留（jar 只在 dist 里由构建生成）
            if 'LionBox-Setup-1.4.0.exe' not in f and not f.endswith('.wav'):
                deletes.append(p)

deletes = sorted(set(deletes))
moves = sorted(set(moves))

# 【必须做】挪走的目录里，别再单独删它里面的文件 —— 否则先删掉一半、再挪走空壳，
# 文件就真没了（_box_bios 里那两个 ROM 就是这么被"删+挪"同时命中的）。
def inside_move(path):
    for m in moves:
        if path == m or path.startswith(m + os.sep):
            return True
    return False


deletes = [p for p in deletes if not inside_move(p)]
keep_total = sum(size_of(k) for k in keeps)
print('== 会删掉 ==')
d_total = 0
for p in deletes:
    s = size_of(p)
    d_total += s
    tag = '（git 里有，可恢复）' if os.path.relpath(p, ROOT).replace('\\', '/') in TR else '（删了就没了）'
    print('  %10s  %-58s %s' % (human(s), os.path.relpath(p, ROOT)[:58], tag))
print('  小计 %s，共 %d 项' % (human(d_total), len(deletes)))

print()
print('== 会挪到项目外（%s）==' % MOVED_OUT)
m_total = 0
for p in moves:
    s = size_of(p)
    m_total += s
    print('  %10s  %s' % (human(s), os.path.relpath(p, ROOT)))
print('  小计 %s，共 %d 项' % (human(m_total), len(moves)))

print()
print('== 保留 ==')
print('  代码/md/安装包 %d 个文件，%s' % (len(keeps), human(keep_total)))

if not GO:
    print()
    print('（这是 dry run，什么都没动。要真清理：python _cleanup.py go）')
    sys.exit(0)

for p in deletes:
    try:
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
        else:
            os.remove(p)
    except OSError as e:
        print('  删不掉 %s: %s' % (p, e))
for p in moves:
    dst = os.path.join(MOVED_OUT, os.path.basename(p))
    os.makedirs(MOVED_OUT, exist_ok=True)
    if os.path.exists(dst):
        shutil.rmtree(dst, ignore_errors=True)
    shutil.move(p, dst)
print()
print('清理完成：删掉 %s，挪走 %s' % (human(d_total), human(m_total)))
print('项目现在剩：')
for name in sorted(os.listdir(ROOT)):
    p = os.path.join(ROOT, name)
    print('  %10s  %s' % (human(size_of(p)), name))
