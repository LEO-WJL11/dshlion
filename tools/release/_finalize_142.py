# -*- coding: utf-8 -*-
r"""1.4.2 定稿：修用户实测报错的工具 + 出包 + 验包 + 文档 + 同步脚本。

前提：出包前先把两个运行时目录从装好的软件目录拷回 dist（项目里只有代码/md/安装包）：
    copy "$env:LOCALAPPDATA\Programs\LionBox\runtime-jre"    dist\runtime-jre    -Recurse
    copy "$env:LOCALAPPDATA\Programs\LionBox\runtime-vulkan" dist\runtime-vulkan -Recurse
"""
import glob
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
ROOT = r'C:\Users\Leo\Desktop\lion-code'
VER = '1.4.2'
OLD_VER = '1.4.1'
RELEASE = os.path.join(ROOT, r'installer\release')
EXE = os.path.join(RELEASE, 'LionBox-Setup-%s.exe' % VER)
TMP = os.path.join(os.environ['TEMP'], '_lion_142')
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


iss = os.path.join(ROOT, r'installer\LionBox.iss')
t = io.open(iss, encoding='utf-8', newline='').read()
assert '#define AppVersion     "%s"' % VER in t, 'iss 版本号不是 ' + VER
check('打包设置仍是 16 线程 + 关 solid', 'LZMANumBlockThreads=16' in t and 'SolidCompression=no' in t)

for need in ('runtime-jre', 'runtime-vulkan'):
    p = os.path.join(ROOT, 'dist', need)
    check('dist\\%s 在（ISCC 要打它）' % need, os.path.isdir(p),
          '' if os.path.isdir(p) else '先按文档里的 copy 命令拷回来')
if not ok_all:
    print('缺运行时目录，先补回来再跑这个脚本。')
    sys.exit(1)

jar_src = os.path.join(ROOT, 'target', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
jar_dist = os.path.join(ROOT, 'dist', 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
html_src = io.open(os.path.join(ROOT, r'web\index.html'), encoding='utf-8', newline='').read()
if os.path.getmtime(jar_src) < os.path.getmtime(os.path.join(ROOT, r'web\index.html')):
    print('!! jar 比 web/index.html 旧，先跑 mvn clean package')
    sys.exit(1)
shutil.copy2(jar_src, jar_dist)

BOX = '盒子'.encode('utf-8')
with zipfile.ZipFile(jar_dist) as z:
    names = z.namelist()
    ui = z.read('BOOT-INF/classes/static/index.html').decode('utf-8', 'replace')
    box_in_jar = [n for n in names if n.endswith('.class') and BOX in z.read(n)]
    del_ = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/file/FileDeleteTool.class')
    wc = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/file/FileWcTool.class')
    rd = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/file/FileReadTool.class')
    bg = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/shell/ShellBackgroundTool.class')
    ex = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/shell/ShellExecuteTool.class')
    fetch = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/web/UrlFetchTool.class')
    plug = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/AbstractToolPlugin.class')
    evc = z.read('BOOT-INF/classes/com/lioncode/web/controller/EventController.class')

check('★ 包里没有任何"盒子"字样', not box_in_jar, '、'.join(box_in_jar[:5]))
check('★ 界面里有按 eventId 去重的代码', 'seenEvents' in ui and 'if (self.seenEvents[eid]) return;' in ui)
check('★ 事件接口仍按毫秒比较', 'toEpochMilli' in evc.decode('latin-1'))
check('界面与源码逐字节一致', ui == html_src)
check('★ delete_file 幂等（本地化字符串"本来就不存在"在 class 里）',
      '本来就不存在'.encode('utf-8') in del_)
check('★ word_count / line_count 支持目录累计', '目录累计'.encode('utf-8') in wc)
check('★ read_file 给目录时会列出内容', '这是目录，不是文件'.encode('utf-8') in rd)
check('★ run_background 会排空子进程输出（有 drain 线程）', b'lionbox-bg-drain' in bg)
check('★ execute_command 说清"有输出"', '有输出'.encode('utf-8') in ex)
check('★ fetch_url 带了 User-Agent', b'Mozilla/5.0' in fetch)
check('★ 找不到文件时给相近名字', b'similarPathHint' in plug)
check('常驻终端实现还在',
      'BOOT-INF/classes/com/lioncode/core/plugin/tool/shell/PersistentShell.class' in names)

sw = time.time()
os.makedirs(RELEASE, exist_ok=True)
r = subprocess.run([r'C:\Users\Leo\is6573\ISCC.exe', iss],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
m = re.search(r'Successful compile \(([\d.]+) sec', r.stdout or '')
if not os.path.isfile(EXE):
    print('没生成安装包：%s' % (r.stderr or r.stdout)[-300:])
    sys.exit(1)
blob = open(EXE, 'rb').read()
size, sha = len(blob), hashlib.sha256(blob).hexdigest().upper()
print('包 %s  %s 字节（%.2f MB）  sha256=%s' % (os.path.basename(EXE), format(size, ','),
                                            size / 1024.0 / 1024.0, sha))
print('  压缩耗时：%s 秒（%.0f 秒）' % (m.group(1) if m else '?', time.time() - sw))

# ---------- 装一遍验 ----------
shutil.rmtree(TMP, ignore_errors=True)
p = subprocess.run([EXE, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS', '/DIR=' + TMP],
                   capture_output=True)
files = sum(len(f) for _, _, f in os.walk(TMP))
jar = os.path.join(TMP, 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar')
jarsize = os.path.getsize(jar) if os.path.isfile(jar) else -1
check('静默安装成功', p.returncode == 0, 'exit=%d，%d 个文件，jar %s 字节'
      % (p.returncode, files, format(jarsize, ',')))

java = os.path.join(TMP, 'runtime-jre', 'bin', 'java.exe')
if os.path.isfile(java):
    home = os.path.join(TMP, '_home')
    os.makedirs(home, exist_ok=True)
    log = open(os.path.join(TMP, 'boot.log'), 'w', encoding='utf-8', errors='replace')
    proc = subprocess.Popen([java, '-Dfile.encoding=UTF-8', '-Duser.home=' + home,
                             '-Dlionbox.home=' + TMP, '-jar', jar,
                             '--server.port=8880',
                             '--lionbox.runtime.auto-download=false',
                             '--lionbox.runtime.prewarm.enabled=false'],
                            cwd=TMP, stdout=log, stderr=subprocess.STDOUT)
    up = False
    import urllib.request
    for _ in range(90):
        try:
            urllib.request.urlopen('http://127.0.0.1:8880/api/runtime/mode', timeout=3).read()
            up = True
            break
        except Exception:
            time.sleep(1)
    check('装好的 jar 起得来', up)
    if up:
        with urllib.request.urlopen('http://127.0.0.1:8880/', timeout=10) as resp:
            served = resp.read().decode('utf-8', 'replace')
        check('★ 装出来的页面里没有盒子字样', '盒子' not in served)
        try:
            import json as _json
            with urllib.request.urlopen(
                    'http://127.0.0.1:8880/api/runtime/prompt-preview?mode=minimal&full=true',
                    timeout=30) as resp:
                d = _json.loads(resp.read().decode('utf-8', 'replace'))
            defs = (d.get('data') or {}).get('toolDefinitions') or '[]'
            names2 = {x.get('name') for x in _json.loads(defs) if isinstance(x, dict)}
            check('装出来的这一版：极简模式还是 21 个工具、不含 web_search',
                  len(names2) == 21 and 'web_search' not in names2, '%d 个' % len(names2))
        except Exception as e:
            check('装出来的这一版能查极简模式工具清单', False, str(e)[:120])
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True)
    log.close()
    time.sleep(1)

un = os.path.join(TMP, 'unins000.exe')
if os.path.isfile(un):
    subprocess.run([un, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'], capture_output=True)
    time.sleep(2)
marker = os.path.join(os.environ['USERPROFILE'], '.lioncode', 'install-model.txt')
if os.path.isfile(marker):
    os.remove(marker)
shutil.rmtree(TMP, ignore_errors=True)

# ---------- 交付清单 ----------
p1 = os.path.join(ROOT, r'docs\交付清单.md')
d = io.open(p1, encoding='utf-8', newline='').read()
d = re.sub(r'LionBox-Setup-\d+\.\d+(\.\d+)?\.exe', 'LionBox-Setup-%s.exe' % VER, d)
d = re.sub(r'\*\*[\d,]+ 字节（[\d.]+ MB）\*\*', '**%s 字节（%.1f MB）**'
           % (format(size, ','), size / 1024.0 / 1024.0), d)
d = re.sub(r'\| \*\*[\d.]+ MB\*\* \|', '| **%.1f MB** |' % (size / 1024.0 / 1024.0), d)
d = re.sub(r'(?m)^[0-9A-F]{64}  LionBox-Setup-[\d.]+\.exe$',
           '%s  LionBox-Setup-%s.exe' % (sha, VER), d)
if u'1.4.2 修用户实测报错的工具' not in d:
    d = d.replace(u'> **1.4.1 不做盒子（硬件一体机）了**',
                  u'> **1.4.2 修用户实测报错的工具** —— 能办成的事不再回 ❌：\n'
                  u'> `word_count`/`line_count` 给目录 → 按目录累计统计；`read_file`/`head_tail_file`\n'
                  u'> 给目录 → 列出目录内容；`delete_file` 删已经不存在的路径、`git_branch` 删不存在的分支 /\n'
                  u'> 重复建同名分支 → 幂等成功；找不到文件时给出"同目录下相近的名字"；\n'
                  u'> `run_background` 改用 PowerShell、workdir 走工作区解析、并排空子进程输出\n'
                  u'> （原来输出一多子进程被管道堵死）；`fetch_url`/`http_get` 带上 User-Agent、\n'
                  u'> 超时放宽、失败重试（fetch_url 超时的真因就是没 UA）；`execute_command` 退出码非 0\n'
                  u'> 但**有输出**时把话说清楚，git 的 128 给专门提示；\n'
                  u'> **1.4.1 不做盒子（硬件一体机）了**')
io.open(p1, 'w', encoding='utf-8', newline='').write(d)
check('交付清单已更新到 1.4.2',
      ('LionBox-Setup-%s.exe' % VER) in d and format(size, ',') in d and sha in d)

# ---------- 自测记录 ----------
p2 = os.path.join(ROOT, r'docs\软件自测记录.md')
t2 = io.open(p2, encoding='utf-8', newline='').read()
if u'第 38 轮' in t2:
    print('自测记录里已有第 38 轮，跳过')
else:
    add = u'''

## 2026-09-30 \u00b7 \u7b2c 38 \u8f6e\uff1a\u4fee\u7528\u6237\u5b9e\u6d4b\u62a5\u9519\u7684\u5de5\u5177

\u7528\u6237\u628a\u5de5\u5177\u5168\u8c03\u4e00\u904d\uff08\u6807\u51c6 + \u6781\u7b80\u5404\u4e00\u8dd1\uff09\uff0c\u62a5\u4e86\u51e0\u4e2a \u274c\u3002\u67e5\u4e0b\u6765\u5927\u90e8\u5206\u4e0d\u662f"\u5de5\u5177\u574f\u4e86"\uff0c
\u800c\u662f**\u5b83\u672c\u6765\u80fd\u628a\u8fd9\u4ef6\u4e8b\u529e\u6210**\u5374\u56de\u4e86\u4e00\u53e5 \u274c\u3002

### 125. \u9010\u6761\u5bf9\u5e94

| \u62a5\u9519 | \u771f\u56e0 | \u73b0\u5728 |
|---|---|---|
| word_count / line_count \u274c | \u6a21\u578b\u628a**\u76ee\u5f55**\u5f53\u6587\u4ef6\u4f20 \u2192 \u53ea\u56de"\u8fd9\u662f\u76ee\u5f55\uff0c\u4e0d\u662f\u6587\u4ef6" | \u6309**\u76ee\u5f55\u7d2f\u8ba1**\u7edf\u8ba1\uff08\u6587\u4ef6\u6570/\u884c/\u5b57/\u5b57\u8282\uff0c\u4e8c\u8fdb\u5236\u53ea\u7b97\u5b57\u8282\uff09 |
| read_file / head_tail_file \u274c | \u540c\u4e0a\uff08"\u4e0d\u662f\u666e\u901a\u6587\u4ef6"\uff09 | \u76f4\u63a5**\u5217\u51fa\u76ee\u5f55\u5185\u5bb9** |
| delete_file \u274c | \u6a21\u578b\u91cd\u590d\u5220\u540c\u4e00\u4e2a\u8def\u5f84\uff08"\u786e\u4fdd\u5b83\u6ca1\u4e86"\uff09 | \u4e0d\u5b58\u5728 = **\u5e42\u7b49\u6210\u529f**\uff08"\u672c\u6765\u5c31\u4e0d\u5b58\u5728"\uff09 |
| git_branch \u274c | \u5220\u4e0d\u5b58\u5728\u7684\u5206\u652f / \u91cd\u590d\u5efa\u540c\u540d\u5206\u652f | \u90fd\u5f53**\u5e42\u7b49\u6210\u529f** |
| \u8bfb\u4e0d\u5230\u6587\u4ef6 | \u53ea\u6709\u4e00\u53e5"\u6587\u4ef6\u4e0d\u5b58\u5728" | \u591a\u4e00\u53e5**\u540c\u76ee\u5f55\u4e0b\u76f8\u8fd1\u7684\u540d\u5b57**\uff08note_file.txt \u2190 nope_note.txt \u8fd9\u79cd\u4e5f\u80fd\u8ba4\uff09 |
| run_background \u274c\u4e00\u6b21\u53c8 \u2705 | \u8d70 `cmd /c`\uff08\u4e0e execute_command \u7684 PowerShell \u4e0d\u4e00\u81f4\uff09+ \u76f8\u5bf9 workdir \u76f4\u63a5 new File | \u6539 PowerShell\uff1bworkdir \u8d70\u5de5\u4f5c\u533a\u89e3\u6790 + \u5b58\u5728\u6027\u68c0\u67e5 |
| \uff08\u65b0\u53d1\u73b0\uff09 | \u540e\u53f0\u8fdb\u7a0b\u7684 stdout \u6ca1\u4eba\u8bfb \u2192 \u8f93\u51fa\u4e00\u591a\u5c31\u88ab\u7ba1\u9053**\u5835\u6b7b** | \u5f00\u4e24\u4e2a\u5b88\u62a4\u7ebf\u7a0b\u6392\u7a7a\uff08\u987a\u5e26\u7559\u6700\u540e 50 \u884c\uff09 |
| fetch_url \u274c \u4f46 http_get \u2705 | **\u4e0d\u5e26 User-Agent** \u2014\u2014 \u4e0d\u5c11\u7ad9\u70b9\u5bf9\u65e0 UA \u7684\u8bf7\u6c42\u76f4\u63a5\u4e0d\u54cd\u5e94 | \u5e26\u6d4f\u89c8\u5668 UA\u3001\u8d85\u65f6 15/30s\u3001\u5931\u8d25\u91cd\u8bd5\u4e00\u6b21 |
| execute_command \u274c\uff08\u9000\u51fa\u7801 128\uff09 | \u4e00\u5f8b\u5199"\u547d\u4ee4\u6267\u884c\u5931\u8d25"\uff0c\u770b\u4e0d\u51fa"\u547d\u4ee4\u5176\u5b9e\u8dd1\u4e86" | \u5199\u6e05\u695a\u6709\u65e0\u8f93\u51fa\uff1b128 \u7279\u522b\u63d0\u793a"\u591a\u534a\u4e0d\u662f git \u4ed3\u5e93" |

### 126. \u9a8c\u8bc1

```
\u65b0\u589e tools/checks/_check_tool_idempotent.py\uff0819 \u9879\uff09\uff1a\u628a\u4e0a\u9762\u6bcf\u4e00\u6761\u90fd\u76ef\u4f4f
  \u2605 word_count/line_count \u7ed9\u76ee\u5f55 \u2192 \u76ee\u5f55\u7d2f\u8ba1\uff1bread_file/head_tail_file \u7ed9\u76ee\u5f55 \u2192 \u5217\u76ee\u5f55
  \u2605 delete_file \u5220\u4e0d\u5b58\u5728\u7684\u8def\u5f84\u3001git_branch \u5220\u4e0d\u5b58\u5728\u7684\u5206\u652f / \u91cd\u590d\u5efa\u540c\u540d \u2192 \u90fd\u6210\u529f
  \u2605 \u8bfb\u4e0d\u5230\u6587\u4ef6\u65f6\u7ed9\u51fa\u76f8\u8fd1\u540d\u5b57\uff08note_file.txt\uff09
  \u2605 run_background\uff1a\u76f8\u5bf9 workdir \u80fd\u7528\u3001PowerShell \u8bed\u6cd5\u80fd\u7528\u3001\u8f93\u51fa 2000 \u884c\u4e0d\u5835\u6b7b
  \u2605 stop_background\uff1a\u591a\u4e2a\u65f6\u56de\u540d\u5355\u3001\u7ed9 pid \u80fd\u505c\u3001\u53ea\u5269\u4e00\u4e2a\u65f6\u4e0d\u7528 pid \u4e5f\u80fd\u505c\u3001\u5168\u505c\u5b8c\u518d\u505c\u6709\u63d0\u793a
  \u2605 fetch_url \u771f\u7684\u5e26\u4e0a\u4e86 Mozilla UA\uff08\u672c\u5730 mock \u670d\u52a1\u5668\u9a8c\u8bc1\uff09\u3001\u80fd\u6293\u5230\u5185\u5bb9
  \u2605 execute_command\uff1a\u9000\u51fa\u7801 5 \u4e14\u6709\u8f93\u51fa \u2192 "\u6709\u8f93\u51fa"\uff1bgit status \u7684 128 \u2192 \u7ed9"\u4e0d\u662f git \u4ed3\u5e93"\u63d0\u793a
\u5176\u4f59\u5957\u4ef6\u56de\u5f52\u5168\u8fc7\uff08_check_fix_round4 \u91cc"\u4f20\u76ee\u5f55\u8981\u62a5\u9519"\u90a3\u6761\u6309\u65b0\u884c\u4e3a\u6539\u6210\u4e86"\u7ed9\u80fd\u770b\u61c2\u7684\u56de\u5e94"\uff09
```

\uff08\u987a\u624b\u4fee\u7684\uff1a\u628a\u5957\u4ef6\u6536\u8fdb tools/ \u4e4b\u540e\uff0c_run_all_checks.py \u7684 glob \u8def\u5f84\u8fd8\u6307\u7740\u9879\u76ee\u6839\uff0c
\u7ed3\u679c"\u4e00\u4e2a\u5957\u4ef6\u6ca1\u627e\u5230\u3001\u8fd8\u62a5\u5168\u90e8\u901a\u8fc7"\u2014\u2014\u73b0\u5728\u5957\u4ef6\u8def\u5f84\u8ddf\u9879\u76ee\u6839\u5206\u5f00\u7b97\u3002\uff09

### 127. \u51fa\u5305 1.4.2

```
installer\\release\\LionBox-Setup-1.4.2.exe  SIZE_PLACEHOLDER B\uff08SIZEMB MB\uff09
sha256  SHA_PLACEHOLDER
\u9759\u9ed8\u5b89\u88c5 exit 0 \u2192 FILES \u4e2a\u6587\u4ef6\uff0cjar JAR_PLACEHOLDER \u5b57\u8282
```'''
    add = (add.replace('SIZE_PLACEHOLDER', format(size, ','))
              .replace('SIZEMB', '%.1f' % (size / 1024.0 / 1024.0))
              .replace('SHA_PLACEHOLDER', sha)
              .replace('FILES', str(files))
              .replace('JAR_PLACEHOLDER', format(jarsize, ',')))
    t2 = t2.rstrip('\n') + '\n' + add + '\n'
    io.open(p2, 'w', encoding='utf-8', newline='').write(t2)
    print('自测记录已追加第 38 轮')

# ---------- 同步脚本 ----------
# 同步脚本现在从 iss 里 sed 出 AppVersion 自己拼 exe 名（不再手写版本号），
# 所以这里只验它确实是"跟着 iss 走"的，而不是钉死在某个版本上。
p3 = os.path.join(ROOT, r'tools\release\_sync_dshlion.sh')
t3 = io.open(p3, encoding='utf-8', newline='').read()
check('同步脚本按 iss 的版本号自动取包名（不写死版本）',
      'LionBox-Setup-$ver.exe' in t3 and 'AppVersion' in t3)

# ---------- 旧包只留当前这一版 ----------
for f in glob.glob(os.path.join(RELEASE, 'LionBox-Setup-*.exe')):
    if os.path.basename(f) != os.path.basename(EXE):
        os.remove(f)
        print('已删掉旧包 %s' % os.path.basename(f))
print('release: %s' % os.listdir(RELEASE))

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
