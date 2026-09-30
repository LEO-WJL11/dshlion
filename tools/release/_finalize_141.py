# -*- coding: utf-8 -*-
r"""1.4.1 定稿：不做盒子了（只改文案）+ 出包 + 验包 + 文档 + 同步脚本。

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
VER = '1.4.1'
RELEASE = os.path.join(ROOT, r'installer\release')
EXE = os.path.join(RELEASE, 'LionBox-Setup-%s.exe' % VER)
TMP = os.path.join(os.environ['TEMP'], '_lion_141')
ok_all = True


def check(label, ok, detail=''):
    global ok_all
    ok_all = ok_all and bool(ok)
    print(('  [OK]   ' if ok else '  [FAIL] ') + label + (('  ' + str(detail)) if detail else ''))


iss = os.path.join(ROOT, r'installer\LionBox.iss')
t = io.open(iss, encoding='utf-8', newline='').read()
assert '#define AppVersion     "%s"' % VER in t, 'iss 版本号不是 ' + VER
check('打包设置仍是 16 线程 + 关 solid', 'LZMANumBlockThreads=16' in t and 'SolidCompression=no' in t)

# 出包前必须有运行时目录，否则 ISCC 会打出一个缺运行时的包
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
    agent = z.read('BOOT-INF/classes/com/lioncode/core/agent/AgentLoop.class')
    shell = z.read('BOOT-INF/classes/com/lioncode/core/plugin/tool/shell/PersistentShell.class')
    evc = z.read('BOOT-INF/classes/com/lioncode/web/controller/EventController.class')
    ui = z.read('BOOT-INF/classes/static/index.html').decode('utf-8', 'replace')
    # 整个 jar 里不该再有"盒子"二字（中文在 class 里是 UTF-8 字节，按字节找）
    box_in_jar = [n for n in names if n.endswith('.class')
                  and BOX in z.read(n)]
check('★ 包里没有任何"盒子"字样（class 里都换成"本地"了）', not box_in_jar,
      '、'.join(box_in_jar[:5]))
check('★ 界面上也不再提盒子', '盒子' not in ui)
check('常驻终端实现还在', 'BOOT-INF/classes/com/lioncode/core/plugin/tool/shell/PersistentShell.class' in names)
check('★ 界面里有按 eventId 去重的代码', 'seenEvents' in ui and 'if (self.seenEvents[eid]) return;' in ui)
check('★ 事件接口仍按毫秒比较', 'toEpochMilli' in evc.decode('latin-1'))
check('界面与源码逐字节一致', ui == html_src)

sw = time.time()
os.makedirs(RELEASE, exist_ok=True)
r = subprocess.run([r'C:\Users\Leo\is6573\ISCC.exe', iss],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
sec = time.time() - sw
m = re.search(r'Successful compile \(([\d.]+) sec', r.stdout or '')
if not os.path.isfile(EXE):
    print('没生成安装包：%s' % (r.stderr or r.stdout)[-300:])
    sys.exit(1)
blob = open(EXE, 'rb').read()
size, sha = len(blob), hashlib.sha256(blob).hexdigest().upper()
print('包 %s  %s 字节（%.2f MB）  sha256=%s' % (os.path.basename(EXE), format(size, ','),
                                            size / 1024.0 / 1024.0, sha))
print('  压缩耗时：%s 秒' % (m.group(1) if m else '?'))

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
        check('★ 装出来的页面里也没有盒子字样', '盒子' not in served)
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
if u'1.4.1 不做盒子了' not in d:
    d = d.replace(u'> **1.4.0 三条**',
                  u'> **1.4.1 不做盒子（硬件一体机）了**：代码/文档/界面里"盒子 / 硬件一体机 /\n'
                  u'> 随盒子交付"这类说法全改成"本地模型运行时"，**只动文案，判断逻辑和端点限制一行没改**；\n'
                  u'> 硬件板的设计文档（E5 主板 / X99 / RK3588S / EasyEDA 那几份）和 740 MB 硬件调研资料\n'
                  u'> 已从项目里删掉；\n'
                  u'> **1.4.0 三条**')
io.open(p1, 'w', encoding='utf-8', newline='').write(d)
check('交付清单已更新到 1.4.1',
      ('LionBox-Setup-%s.exe' % VER) in d and format(size, ',') in d and sha in d)

# ---------- 自测记录 ----------
p2 = os.path.join(ROOT, r'docs\软件自测记录.md')
t2 = io.open(p2, encoding='utf-8', newline='').read()
if u'第 37 轮' in t2:
    print('自测记录里已有第 37 轮，跳过')
else:
    add = u'''

## 2026-09-30 \u00b7 \u7b2c 37 \u8f6e\uff1a\u4e0d\u505a\u76d2\u5b50\u4e86\uff08\u53ea\u6539\u6587\u6848\uff09

\u7528\u6237\uff1a"\u6211\u4e0d\u6253\u7b97\u505a\u76d2\u5b50\u4e86\u5427\u76d2\u5b50\u76f8\u5173\u7684\u5168\u90e8\u5220\u4e86"\u2192\u9009\u4e86"\u53ea\u628a\u6587\u6848\u6539\u6389"\u3002

### 121. \u5220\u6389\u7684\u786c\u4ef6\u4e1c\u897f

- `docs/` \u91cc 7 \u4efd\u786c\u4ef6\u677f\u8bbe\u8ba1\u6587\u6863\uff1aE5\u4e3b\u677f-\u539f\u7406\u56fe\u4ea4\u4ed8\u8bf4\u660e\u3001E5\u4e3b\u677f-\u7535\u6e90\u4e0e\u5916\u8bbe\u8bbe\u8ba1\u3001
  E5\u4e3b\u677f\u539f\u7406\u56fe-\u72b6\u6001\u3001X99\u53c2\u8003\u8bbe\u8ba1-\u62bd\u53d6\u3001\u786c\u4ef6\u8bbe\u8ba1\u89c4\u683c-RK3588S\u3001EasyEDA-API-\u5b9e\u64cd\u7b14\u8bb0\u3001EasyEDA-\u81ea\u5efa\u7b26\u53f7API
- \u9879\u76ee\u5916\u90a3 740 MB \u786c\u4ef6\u8c03\u7814\u8d44\u6599\uff08`_research` / `_hwres` / `usbresearch` / `_box_bios` /
  `_eda_tools` / `dl`\uff09\uff1a\u771f\u5220\u4e86\uff08\u4e4b\u524d\u53ea\u662f\u632a\u51fa\u9879\u76ee\uff09

### 122. \u53ea\u6539\u6587\u6848\uff0c\u884c\u4e3a\u4e00\u884c\u6ca1\u52a8

\u5168\u9879\u76ee 69 \u5904"\u76d2\u5b50"\uff08\u4ee3\u7801\u6ce8\u91ca\u3001\u6587\u6863\u3001\u754c\u9762\u3001application.yml\uff09\u6362\u6210"\u672c\u5730\u6a21\u578b\u8fd0\u884c\u65f6 / \u672c\u5730"\u7684\u8bf4\u6cd5\uff1a

- "**\u672c\u4ea7\u54c1\u4e3a\u786c\u4ef6\u4e00\u4f53\u673a\uff08\u6a21\u578b\u76d2\u5b50\uff09\uff0c\u6a21\u578b\u8fd0\u884c\u65f6\u968f\u76d2\u5b50\u4ea4\u4ed8**" \u2192 "\u672c\u4ea7\u54c1\u7684\u6a21\u578b\u8fd0\u884c\u65f6\u968f\u8f6f\u4ef6\u81ea\u5e26\u3001\u7ed1\u5b9a\u56de\u73af\u5730\u5740"
- \u754c\u9762\u9996\u9875\u90a3\u5f20\u5361\u7247\uff1a"\u76d2\u5b50\u672c\u5730\u63a8\u7406" \u2192 "\u672c\u5730\u63a8\u7406"
- \u7ed9\u7528\u6237\u770b\u7684\u4e00\u53e5\u62a5\u9519\uff1a"\u672c\u4ea7\u54c1\u4ec5\u652f\u6301\u76d2\u5b50\u672c\u5730\u6a21\u578b\u7aef\u70b9\uff08\u56de\u73af\u5730\u5740\uff09" \u2192
  "\u672c\u4ea7\u54c1\u4ec5\u652f\u6301\u672c\u673a\uff08\u56de\u73af\u5730\u5740\uff09\u7684\u6a21\u578b\u7aef\u70b9"
- **\u5224\u65ad\u903b\u8f91\u3001\u7aef\u70b9\u9650\u5236\u3001\u914d\u7f6e\u952e\u4e00\u5f8b\u6ca1\u52a8**\uff08diff \u91cc\u53ea\u6709\u6ce8\u91ca\u548c\u5b57\u7b26\u4e32\uff09

### 123. \u9a8c\u8bc1

```
\u6539\u5b8c\u5168\u9879\u76ee grep"\u76d2\u5b50"\uff1a0 \u5904
mvn clean package\uff1aBUILD SUCCESS
\u56de\u5f52\uff08\u53d7\u5f71\u54cd\u7684 7 \u4e2a\u5957\u4ef6\uff09\uff1a_check_ui_features / _check_ui_api / _check_model_choice /
  _check_tool_channel / _check_param_coercion / _check_multi_tool_round / _check_modes \u5168\u8fc7
\u6253\u51fa\u6765\u7684\u5305\u91cc\u4e5f grep \u4e0d\u5230"\u76d2\u5b50"\uff08\u8fde class \u91cc\u90fd\u6ca1\u6709\uff09
```

### 124. \u51fa\u5305 1.4.1

```
installer\\release\\LionBox-Setup-1.4.1.exe  SIZE_PLACEHOLDER B\uff08SIZEMB MB\uff09
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
    print('自测记录已追加第 37 轮')

# ---------- 同步脚本 ----------
p3 = os.path.join(ROOT, '_sync_dshlion.sh')
t3 = io.open(p3, encoding='utf-8', newline='').read()
t3 = t3.replace('git add -f "installer/release/LionBox-Setup-1.4.0.exe"',
                'git add -f "installer/release/LionBox-Setup-%s.exe"' % VER)
if 'LionBox-Setup-1.4.0.exe"' in t3:
    t3 = t3.replace('git add -f "installer/release/LionBox-Setup-%s.exe"' % VER,
                    'git rm --cached -q "installer/release/LionBox-Setup-1.4.0.exe" 2>/dev/null || true\n'
                    'git add -f "installer/release/LionBox-Setup-%s.exe"' % VER)
io.open(p3, 'w', encoding='utf-8', newline='').write(t3)
check('同步脚本已指向 1.4.1 的包名', 'LionBox-Setup-%s.exe' % VER in t3)

# ---------- 旧包只留当前这一版 ----------
for f in glob.glob(os.path.join(RELEASE, 'LionBox-Setup-*.exe')):
    if os.path.basename(f) != os.path.basename(EXE):
        os.remove(f)
        print('已删掉旧包 %s' % os.path.basename(f))
print('release: %s' % os.listdir(RELEASE))

print('=' * 72)
print('结果：' + ('全部通过' if ok_all else '有失败项'))
sys.exit(0 if ok_all else 1)
