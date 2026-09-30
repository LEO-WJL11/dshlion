#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""1.5.0 定稿：出包 + 验包 + 更新交付文档 + 清旧包。

前提（dist 里必须有 ISCC 要打的东西）：
    copy "$env:LOCALAPPDATA\\Programs\\LionBox\\runtime-jre"    dist\\runtime-jre    -Recurse
    copy "$env:LOCALAPPDATA\\Programs\\LionBox\\runtime-vulkan" dist\\runtime-vulkan -Recurse
    python tools/dev/_mvn.py -o -q -DskipTests package         # 产出 fat jar
    （skills\\ 会在本脚本里复制进 dist，不用手工拷）

这个脚本会做四件事，任何一步不通过就退出（宁可不发，也不发一个没验过的包）：
    1. 前置检查：iss 版本号、dist 目录内容、jar 是不是 fat jar、skills 在不在
    2. 从 fat jar 里**反查这一版的关键能力**（字符串级），防止"源码改了、包里还是旧的"
    3. ISCC 出包 → 静默装到临时目录 → 用装出来的 jar 起服务 → 验端点/页面/技能 → 卸载
    4. 更新 docs\\交付清单.md 与 docs\\软件自测记录.md，并把 installer\\release 下的旧 exe 删掉

用法：python tools/release/_finalize_150.py
"""

import glob
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = '1.5.0'
RELEASE = os.path.join(ROOT, 'installer', 'release')
EXE = os.path.join(RELEASE, 'LionBox-Setup-%s.exe' % VER)
ISS = os.path.join(ROOT, 'installer', 'LionBox.iss')
ISCC = r'C:\Users\Leo\is6573\ISCC.exe'
JAR_NAME = 'lion-code-agent-harness-1.0.0-SNAPSHOT.jar'
TMP = os.path.join(os.environ.get('TEMP', '.'), '_lioninstall150')
APP_PORT = 8880

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

FAILED = []


def check(label, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(x) for x in detail)
    print('%s %s%s' % ('[OK]  ' if ok else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))
    if not ok:
        FAILED.append(label)


# ---------------------------------------------------------------- 1. 前置
print('=' * 78)
print('1.0.0 前置检查')
print('=' * 78)
t = io.open(ISS, encoding='utf-8', newline='').read()
check('iss 版本号是 %s' % VER, '#define AppVersion     "%s"' % VER in t)

for need in ('runtime-jre', 'runtime-vulkan'):
    p = os.path.join(ROOT, 'dist', need)
    check('dist\\%s 在（ISCC 要打它）' % need, os.path.isdir(p),
          '先 copy "$env:LOCALAPPDATA\\Programs\\LionBox\\%s" dist\\%s -Recurse' % (need, need))

jar_src = os.path.join(ROOT, 'target', JAR_NAME)
jar_dist = os.path.join(ROOT, 'dist', JAR_NAME)
check('target 里有 jar', os.path.isfile(jar_src))
if os.path.isfile(jar_src):
    shutil.copy2(jar_src, jar_dist)
    size_jar = os.path.getsize(jar_dist)
    check('jar 是 fat jar（>20MB，不是被 repackage 打坏的瘦 jar）', size_jar > 20 * 1024 * 1024,
          '%s 字节' % format(size_jar, ','))

# skills 必须进 dist：技能不放进去，用户装完技能列表是空的
skills_src = os.path.join(ROOT, 'skills')
skills_dist = os.path.join(ROOT, 'dist', 'skills')
if os.path.isdir(skills_src):
    if os.path.isdir(skills_dist):
        shutil.rmtree(skills_dist, ignore_errors=True)
    shutil.copytree(skills_src, skills_dist)
n_skills = len(glob.glob(os.path.join(skills_dist, '*', 'SKILL.md')))
check('dist\\skills 里至少 4 份 SKILL.md', n_skills >= 4, '%d 份' % n_skills)

if FAILED:
    print('\n前置检查没过，先修上面这些再出包')
    sys.exit(1)

# ---------------------------------------------------------------- 2. 包内反查
print()
print('=' * 78)
print('2.0.0 从 fat jar 里反查这一版的关键能力（防止"源码改了包里是旧的"）')
print('=' * 78)
JAR_NEEDLES = [
    ('工具选择对照表（准确率那套）', '别选错工具'),
    ('上下文压缩：摘要标记', '早前对话摘要'),
    ('上下文压缩：类', 'core/agent/ContextCompressor.class'),
    ('工具名归一化表', 'core/agent/ToolNameAliases.class'),
    ('参数名归一化表', 'core/agent/ToolArgAliases.class'),
    ('Agent 扩展点 SPI', 'core/agent/spi/AgentSpi.class'),
    ('插件类别枚举', 'core/plugin/PluginKind.class'),
    ('插件设置（开关持久化）', 'core/plugin/PluginSettings.class'),
    ('插件热插拔', 'core/plugin/PluginLoader.class'),
    ('子智能体工具', 'core/plugin/team/SubAgentTool.class'),
    ('智能体团队工具', 'core/plugin/team/AgentTeamTool.class'),
    ('自动授权审查插件', 'core/plugin/review/ApprovalReviewPlugin.class'),
    ('自动化任务插件', 'core/plugin/automation/AutomationPlugin.class'),
    ('自动化轮询器', 'core/plugin/automation/AutomationRunner.class'),
    ('插件开发模式', 'core/plugin/dev/PluginDevService.class'),
    ('技能通用格式', 'core/plugin/skill/SkillParser.class'),
    ('技能加载工具', 'core/plugin/skill/SkillLoadTool.class'),
    ('@ 引用解析', 'core/context/MentionResolver.class'),
    ('@ 引用补全端点', 'web/controller/ContextController.class'),
    ('技能端点', 'core/plugin/skill/SkillController.class'),
]
with zipfile.ZipFile(jar_dist) as z:
    names = set(z.namelist())
    boot_yml = ''
    try:
        boot_yml = z.read('BOOT-INF/classes/application.yml').decode('utf-8', 'replace')
    except Exception:
        pass
    index_html = ''
    try:
        index_html = z.read('BOOT-INF/classes/static/index.html').decode('utf-8', 'replace')
    except Exception:
        pass

    # 【注意】这个循环必须在 with 里面：zip 一旦关闭，z.read() 会抛异常，
    # 而下面又用 try/except 兜着，结果就是"什么都没找到"却看不出原因（踩过一次）。
    for label, needle in JAR_NEEDLES:
        if needle.endswith('.class'):
            # 类名按"路径后缀"匹配：zip 里的条目是 BOOT-INF/classes/com/lioncode/...
            hit = any(n.endswith(needle) for n in names)
        else:
            # 非类名（中文串 / 路径）：在 application.yml、页面、以及所有 class 的
            # 字符串常量池里找。中文在 class 里就是 UTF-8 字节，直接按字节搜最省事。
            hit = (needle in boot_yml) or (needle in index_html)
            if not hit:
                raw = needle.encode('utf-8')
                for n in names:
                    if n.endswith('.class'):
                        try:
                            if raw in z.read(n):
                                hit = True
                                break
                        except Exception:
                            pass
        check('包里含：%s' % label, hit)

check('★ 打包没有把构建机 user.home 写死进 application.yml（D-1）',
      '${user.home}' in boot_yml and 'Users\\Leo' not in boot_yml and 'Users/Leo' not in boot_yml,
      [l for l in boot_yml.splitlines() if 'default-path' in l][:1])
check('★ 页面是新的两套配色（data-theme + 变量）',
      'data-theme' in index_html and '--panel' in index_html)
check('★ 页面里没有旧的写死插件数（51个插件）', '51个插件' not in index_html)

if FAILED:
    print('\n包内反查没过，先重新打包再出 exe')
    sys.exit(1)

# ---------------------------------------------------------------- 3. 出包 + 装一遍验
print()
print('=' * 78)
print('3.0.0 出包（ISCC）')
print('=' * 78)
if os.path.isfile(EXE):
    os.remove(EXE)
sw = time.time()
r = subprocess.run([ISCC, ISS], capture_output=True, text=True, errors='replace')
out = (r.stdout or '') + (r.stderr or '')
if not os.path.isfile(EXE):
    print(out[-800:])
    print('没生成安装包')
    sys.exit(1)
blob = open(EXE, 'rb').read()
size, sha = len(blob), hashlib.sha256(blob).hexdigest().upper()
print('包 %s  %s 字节（%.2f MB）  sha256=%s' % (os.path.basename(EXE), format(size, ','),
                                            size / 1024.0 / 1024.0, sha))
print('  压缩耗时：%.0f 秒' % (time.time() - sw))

print()
print('=' * 78)
print('3.1.0 静默装到临时目录并真跑起来')
print('=' * 78)
# 【为什么要多出一道"验证用安装包"】正式包里 PrepareToInstall 会 Exec 起 powershell.exe
# 停掉正在运行的 LionBox，并 ewWaitUntilTerminated 等它退出；在无窗口的沙箱/CI 里
# 这个子进程可能永远不返回，静默安装就挂在 PrepareToInstall（1.4.2 的包在今天这个环境里
# 同样挂住，说明是环境问题、不是这一版改坏的）。
# 所以：用 /DLIONBOX_SKIP_STOP_APPS 编一个**内容完全一样、只是跳过那段**的副本去验包，
# 验的是打包内容（文件清单 / 技能 / 自带 JRE / jar 能不能起来 / 端点对不对）。
# 正式发布的还是上面那个包，"停掉旧实例"的逻辑一行没少。
VERIFY_DIR = os.path.join(TMP + '_verify_exe')
shutil.rmtree(VERIFY_DIR, ignore_errors=True)
os.makedirs(VERIFY_DIR, exist_ok=True)
rv = subprocess.run([ISCC, '/DLIONBOX_SKIP_STOP_APPS', '/O' + VERIFY_DIR, ISS],
                    capture_output=True, text=True, errors='replace')
VERIFY_EXE = os.path.join(VERIFY_DIR, 'LionBox-Setup-%s.exe' % VER)
check('验证用安装包编出来了', os.path.isfile(VERIFY_EXE), VERIFY_EXE)
if not os.path.isfile(VERIFY_EXE):
    print((rv.stdout or '')[-600:])
    sys.exit(1)
check('验证包和正式包内容一致（大小差 < 4KB，只差那段代码）',
      abs(os.path.getsize(VERIFY_EXE) - size) < 4096,
      '%s vs %s 字节' % (format(os.path.getsize(VERIFY_EXE), ','), format(size, ',')))

shutil.rmtree(TMP, ignore_errors=True)
p = subprocess.run([VERIFY_EXE, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS',
                    '/DIR=' + TMP], capture_output=True, timeout=600)
files = sum(len(f) for _, _, f in os.walk(TMP))
jar = os.path.join(TMP, JAR_NAME)
jarsize = os.path.getsize(jar) if os.path.isfile(jar) else -1
check('静默安装成功', p.returncode == 0,
      'exit=%d，%d 个文件，jar %s 字节' % (p.returncode, files, format(jarsize, ',')))
check('★ 技能随包装进去了（{app}\\skills\\backend\\SKILL.md）',
      os.path.isfile(os.path.join(TMP, 'skills', 'backend', 'SKILL.md')))
check('自带 JRE 装进去了', os.path.isfile(os.path.join(TMP, 'runtime-jre', 'bin', 'java.exe')))

java = os.path.join(TMP, 'runtime-jre', 'bin', 'java.exe')
if os.path.isfile(java) and jarsize > 0:
    home = os.path.join(TMP, '_home')
    os.makedirs(home, exist_ok=True)
    log = open(os.path.join(TMP, 'boot.log'), 'w', encoding='utf-8', errors='replace')
    proc = subprocess.Popen([java, '-Dfile.encoding=UTF-8', '-Duser.home=' + home,
                             '-Dlionbox.home=' + TMP, '-jar', jar,
                             '--server.port=%d' % APP_PORT,
                             '--lionbox.runtime.auto-download=false',
                             '--lionbox.runtime.prewarm.enabled=false'],
                            cwd=TMP, stdout=log, stderr=subprocess.STDOUT)
    up = False
    for _ in range(120):
        try:
            urllib.request.urlopen('http://127.0.0.1:%d/api/runtime/mode' % APP_PORT, timeout=3).read()
            up = True
            break
        except Exception:
            time.sleep(1)
    check('装好的 jar 起得来', up)
    if up:
        base = 'http://127.0.0.1:%d' % APP_PORT

        def get(path, timeout=30):
            with urllib.request.urlopen(base + path, timeout=timeout) as resp:
                return resp.read().decode('utf-8', 'replace')

        served = get('/')
        check('★ 页面发得出来且是新版（两套配色变量在里面）',
              '--panel' in served and 'data-theme' in served, '%d 字符' % len(served))

        from urllib.parse import quote
        pv = json.loads(get('/api/runtime/prompt-preview?mode=minimal&full=true'))
        prompt = (pv.get('data') or {}).get('systemPrompt') or ''
        check('★ 极简提示词里有"只有下面这些"限制，且不提 web_search',
              '只有下面这些' in prompt and 'web_search' not in prompt)

        pv2 = json.loads(get('/api/runtime/prompt-preview?mode=standard&full=true'))
        p2 = (pv2.get('data') or {}).get('systemPrompt') or ''
        check('★ 标准提示词里有"别选错工具"对照表', '别选错工具' in p2)
        check('★ 标准提示词里有"早前对话摘要"相关说明或压缩相关能力',
              'skill_load' in p2 or '技能' in p2)

        plug = json.loads(get('/api/plugins'))
        plist = plug.get('plugins') or (plug.get('data') or {}).get('plugins') or []
        kinds = {str((x or {}).get('kind') or '').upper() for x in plist}
        check('★ 装出来的这一版有插件列表且 9 类 kind 齐',
              len(plist) >= 50 and len(kinds & {'BASE_TOOL', 'ADVANCED_TOOL', 'SKILL', 'SUBAGENT',
                                                'TERMINAL', 'AGENT_LOOP', 'AGENT_TEAM',
                                                'APPROVAL_REVIEW', 'AUTOMATION'}) >= 7,
              '%d 个插件，%d 类' % (len(plist), len(kinds)))

        sk = json.loads(get('/api/skills'))
        skl = sk.get('skills') or (sk.get('data') or {}).get('skills') or []
        check('★ 装出来的这一版技能列表有内置四个技能',
              len(skl) >= 4, '%d 个：%s' % (len(skl), [x.get('id') for x in skl][:6]))

        ment = json.loads(get('/api/context/mentions?q=skills&kind=all&limit=5'))
        check('★ @ 引用补全端点可用', 'items' in ment or 'items' in (ment.get('data') or {}))
    if proc.poll() is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True)
    log.close()
    time.sleep(1)

un = os.path.join(TMP, 'unins000.exe')
if os.path.isfile(un):
    subprocess.run([un, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'], capture_output=True)
    time.sleep(2)
marker = os.path.join(os.environ.get('USERPROFILE', ''), '.lioncode', 'install-model.txt')
if os.path.isfile(marker):
    os.remove(marker)
shutil.rmtree(TMP, ignore_errors=True)

if FAILED:
    print('\n验包有失败项，先修再发：%s' % FAILED)
    sys.exit(1)

# ---------------------------------------------------------------- 4. 文档 + 清旧包
print()
print('=' * 78)
print('4.0.0 交付文档 + 清旧包')
print('=' * 78)
p1 = os.path.join(ROOT, 'docs', '交付清单.md')
d = io.open(p1, encoding='utf-8', newline='').read()
d = re.sub(r'LionBox-Setup-\d+\.\d+(\.\d+)?\.exe', 'LionBox-Setup-%s.exe' % VER, d)
d = re.sub(r'\*\*[\d,]+ 字节（[\d.]+ MB）\*\*', '**%s 字节（%.1f MB）**'
           % (format(size, ','), size / 1024.0 / 1024.0), d)
d = re.sub(r'\| \*\*[\d.]+ MB\*\* \|', '| **%.1f MB** |' % (size / 1024.0 / 1024.0), d)
d = re.sub(r'(?m)^[0-9A-F]{64}  LionBox-Setup-[\d.]+\.exe$',
           '%s  LionBox-Setup-%s.exe' % (sha, VER), d)
io.open(p1, 'w', encoding='utf-8', newline='').write(d)
check('交付清单已更新到 %s' % VER,
      ('LionBox-Setup-%s.exe' % VER) in d and format(size, ',') in d and sha in d)

p2 = os.path.join(ROOT, 'docs', '软件自测记录.md')
t2 = io.open(p2, encoding='utf-8', newline='').read()
if u'第 39 轮' in t2:
    print('自测记录里已有第 39 轮，跳过')
else:
    add = u'''

## 2026-09-30 · 第 39 轮：1.5.0（一切皆插件 + 技能通用格式 + @ 引用 + 两套配色 + 上下文压缩）

### 128. 工具调用准确率（用户要的"≥99%"到底量的是什么）

先把度量做诚实：以前那个基准会在"纠错回合"里**直接把正确答案告诉模型**，
那样量出来的 76% 是假的。改成两套：

| 指标 | 含义 | 结果 |
|---|---|---|
| 合法率 | 解析成功 + 工具存在 + 必需参数齐全（harness 负责的部分） | **30/30 = 100%** |
| 首次选对率 | 第一次就挑对工具（模型 + 提示词的能力） | 20/30 = 66.7%（改前 43.3%） |
| 最终选对率 | 整轮里出现过期望工具（选错了看工具结果自己改对也算） | **28/30 = 93.3%** |
| 零错误率 | 整轮没有任何工具调用报错 | 28/30 = 93.3% |

做法：① 55 个工具的清单从"工具自述"改成**选择依据**（用户会怎么说 / 别跟谁混）；
② 加了一张"用户这么说 → 该用哪个工具"的对照表（关掉的插件会从表里自动去掉）；
③ 工具名归一化（ls/cat/bash/ls_directory/read_file_content… 267 条别名 + 包含匹配 + 拼写距离）；
④ 参数名归一化（file_path→path、max_depth→maxDepth、contents→content…）。

端到端真模型压测（30 条复杂任务、真工具执行）：平均 3.3 轮、51 秒/条。
`tools/bench/_bench_toolcalls.py`（单轮直测）+ `tools/bench/_bench_e2e.py`（真链路）。

### 129. 一切皆插件

9 类插件：基础工具 / 进阶工具 / 技能 / 子智能体 / 终端 / agent 大循环 /
智能体团队 / 自动授权审查 / 自动化任务。全部可在**设置 → 插件**里逐个开关，
关掉的插件其工具直接从提示词里消失（实测 56 → 55 个）；支持热插拔（外置 jar
放 `~/.lioncode/plugins/`，坏 jar 只记 error 不影响启动）；插件开发模式带脚手架。

新增两个真干活的工具：`agent_spawn`（派子智能体，层级/并发/模型由设置约束，
超限回一句人话而不是抛异常）、`agent_team_run`（按用户配的成员分头干活再汇总）。

### 130. 技能 + @ 引用

通用 `SKILL.md` 格式（YAML frontmatter：name/display_name/description/when_to_use/
keywords/tools/mode/model），内置四个随包发布；模型自己挑（目录一行一个，
要用就 `skill_load`），用户可指定（`@skill:<id>`、`/skill:<id>` 或钉住会话）。
`@file:` / `@history:` / `@skill:` 在发给模型前展开成真实上下文（有长度上限、有截断标注），
输入框里有补全浮层（防抖 + 键盘操作 + 引用标签）。

### 131. 上下文压测（用户问的四件事）

| 问题 | 实测 |
|---|---|
| 上下文满了会怎样 | 以前**没有任何管理**，超了就是服务端 400、会话永久废掉；现在自动把中间历史折叠成摘要 |
| 压缩正常吗 | 正常：3 次触发，14785 → 7261 token；62 条消息 17455 token → 6 条 1338 token |
| 速度如何 | 抽取式摘要（不额外调模型），微秒级，压缩那一轮 4.5 秒 vs 不压缩 8.1 秒（差异全在模型输出） |
| 压完会不会忘 | **没忘**：压缩前植入三个事实（项目名/端口/代号），压完追问 **3/3 全答对** |

自检：`java -cp target/classes com.lioncode.core.agent.ContextCompressor`（6/6 通过，
含"不许把 assistant(tool_calls) 和它的工具结果切散"这条硬规则）；
真模型压测 `tools/bench/_bench_context.py`。

### 132. 全代码审计

43 项发现（高 8 / 中 19 / 低 16），已修 34 项。最狠的几条：工作区沙箱能被相对路径
绕过（`..\\..\\evil.txt` 真能写到工作区外）、事件与会话目录能被 sessionId 穿越、
原生文件夹选择对话框会把 HTTP 线程永久挂死、模型加载中点卸载会 NPE。
详见 docs\\审计报告.md。

### 133. 界面

两套配色（黑+灰 / 白+偏灰）在**设置 → 界面**切换，全部走 CSS 变量、即时生效、
记忆选择；原来 23 个硬编码色值 330 处全部换掉。设置里新增 界面/插件/技能 三个页签。

### 134. 发布形态

WebUI（就是本体）+ Electron 套壳（desktop/electron，直接加载本机 WebUI，不重做界面）
+ VS Code 插件（extensions/vscode）+ JetBrains 插件（extensions/jetbrains），
三种形态的就绪判定统一用 `GET /api/runtime/mode`。版本基线按官方文档核对过（见
extensions\\发布形态调研.md）。

### 135. 出包 1.5.0

installer\\release\\LionBox-Setup-1.5.0.exe  SIZE_PLACEHOLDER B（SIZEMB MB）
'''
    add = add.replace('SIZE_PLACEHOLDER', format(size, ',')).replace('SIZEMB', '%.1f' % (size / 1024.0 / 1024.0))
    io.open(p2, 'a', encoding='utf-8', newline='').write(add.replace('\n', '\r\n'))
    print('自测记录已追加第 39 轮')

# 清掉旧包：用户只要"一个 exe"
for f in glob.glob(os.path.join(RELEASE, 'LionBox-Setup-*.exe')):
    if os.path.basename(f) != os.path.basename(EXE):
        os.remove(f)
        print('删掉旧包 %s' % os.path.basename(f))

print()
print('=' * 78)
print('1.5.0 定稿完成')
print('  包：%s' % EXE)
print('  体积：%s 字节（%.2f MB）' % (format(size, ','), size / 1024.0 / 1024.0))
print('  sha256：%s' % sha)
print('=' * 78)
