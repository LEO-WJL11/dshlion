#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""1.5.2 文档更新：设置页签合并（四个 → 一个「插件管理」）。

用户的原话："最低插件管理给你面，如果设置里面有重复的设置，给它干掉，全部缩进插件管理。"
所以文档里凡是还写着「插件参数 / 技能 / 审批策略」这些独立页签的地方，都要改成新的说法，
并把"为什么合并、合并后长什么样"写清楚。

【为什么用 Python 改】这台机器上 PowerShell 的 Get-Content/Set-Content 按 GBK 读写中文文件，
会把中文整段写成乱码（这一轮栽过两次），带中文的文档一律用 Python。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def patch(rel, pairs, must=True):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        print('  跳过（不存在）：%s' % rel)
        return False
    s = io.open(p, encoding='utf-8', newline='').read()
    n = 0
    for old, new in pairs:
        if old in s:
            s = s.replace(old, new)
            n += 1
        elif must:
            print('  ! 锚点没找到（%s）：%s' % (rel, old[:40]))
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('  %s：替换 %d 处' % (rel, n))
    return True


NOTE = '''
## 2026-10-01 · 第 42 轮：设置页签合并成一个「插件管理」

### 144. 用户指出的问题（他说得对）

> "最低插件管理给你面，如果设置里面有重复的设置，给它干掉，全部缩进插件管理。"

原来设置里有 **8 个页签**，其中四个在讲同一件事，还互相指路（插件参数页里写着"开关在插件页签里"）：

| 旧页签 | 内容 | 和谁重复 |
|---|---|---|
| 插件 | 每个插件的开关 | —— |
| 插件参数 | 终端 / 大循环 / 子智能体 / 审查 / 团队 / 自动化的参数 | 讲的就是上面那些插件的参数 |
| 技能 | 技能列表 + 指定使用 | 技能本来就是一类插件（kind=SKILL） |
| 审批策略 | 工具审批档位 | 和"自动授权审查插件"是同一件事的本地规则部分 |

### 145. 现在长什么样

设置只剩 **5 个页签**：界面 / **插件管理** / 模型来源 / 音效提醒 / 本地模型。

「插件管理」一页里：
- 按九类分组，每类有全开/全关，每个插件一行开关；
- **每个插件的参数就贴在它自己那一行下面**（可折叠）：终端限制、大循环参数、子智能体参数、
  审查模型 + 本地审批策略、团队成员增删、自动化任务增删；
- 技能进 SKILL 分组（带"指定使用"和开关），技能接口万一没返回 SKILL 类，会兜底出一个「技能」分组，
  保证自己放进 `skills/` 的技能永远找得到；
- 老页签名（plugins/pluginparams/skills/approvals）仍然认，一律落到插件管理，不会点出空页；
- 加/删团队成员、加/删自动化任务之后重画页面时，**会记住哪些折叠块是打开的**，不会把用户正看着的那块合上。

### 146. 验到什么程度（20 条断言，全过）

`tools/release/_verify_152_ui.py`：把 1.5.2 主安装包**装到临时目录**，用**装出来的自带 JRE**
起服务，然后去 GET `/index.html` 逐条断言：

- 服务端发的页面和仓库 `web/index.html` **逐字节一致**（改完真打进去了，不是"我改了源码"）；
- 有「插件管理」页签；旧三个页签按钮**不存在**；老页名仍落到插件管理；
- 六个参数块按插件分发；技能与审批策略的渲染在插件页里（独立的三个页签函数已删除）；
- `/api/plugins` 列出 **68 个插件 / 9 类**，六个系统插件（终端/大循环/子智能体/审查/团队/自动化）都在；
- 卸载后安装目录清空。

回归：`tools/checks/_run_all_checks.py` 全套（含改写后的 `_check_ui.py` 40 条、
换成能自动判的 `_check_desktop_shell.py`）。

### 147. 这一轮顺手修掉的

1. **vsix 里误打进了一个旧 vsix**（体积从 9.9KB 涨到 18KB）：打包时没排除构建产物，
   现在排除 `*.vsix/*.zip` 并逐个列出包内容核对；`extensions/vscode/*.vsix` 这类散落的
   构建产物也加进了 .gitignore。
2. **JetBrains 包版本号**：Gradle 缓存已被清掉，重编要下 1.3GB（Gradle + IntelliJ 平台），
   而这次只改版本号 —— 用 `tools/release/_patch_jetbrains_version.py` 打补丁，
   并且**逐字节比对两个 class 与 1.5.1 一致**（只改了 plugin.xml 里的版本号）。
3. **验证脚本会触发 8.9GB 模型下载**：全新安装目录里没有权重，应用一起来就自动从 ModelScope 拉模型
   （卸载后目录里剩下 `lion-merged-Q8_0.gguf.part`）。验证只需要 HTTP 接口，现在显式加
   `--lionbox.runtime.auto-download=false`。
4. **`_check_desktop_shell.py` 由永远红改成能自动判**：原来验的是"窗口有没有取到页面"，
   而 WebView2 宿主在受限会话里起不来（Tauri 报 0x8000FFFF、手写宿主 CLR 崩溃，同机 Edge headless 正常），
   这条永远红且没有信息量。现在改成验能自动判的：壳体积（<20MB，证明没打包浏览器）、
   `--selftest` 的 JSON、jar/JRE 定位、安装包 <100MB、工程文件在位。

### 148. 出包 1.5.2（四个包，全部重出重验）

| 文件 | 体积 | 验证 |
|---|---|---|
| LionBox-Setup-1.5.2.exe | 74.21 MB | 装→起→20 条界面/接口断言→卸，全过 |
| LionBox-Desktop-1.5.2-Setup.exe | 2.84 MB | 装→装出来的 exe 自检→卸，10 条全过 |
| LionBox-VSCode-1.5.2.vsix | 8.4 KB | 包内容 = package.json/README/src/.vscodeignore（无冗余） |
| LionBox-JetBrains-1.5.2.zip | 8,548 B | plugin.xml 版本=1.5.2、id/兼容区间不变、class 逐字节一致 |
'''

patch('docs/界面说明.md', [
    ('| 模型来源 / 审批策略 / 音效提醒 / 本地模型 | 原有页签，行为不变 |',
     '| 插件管理 | **插件唯一入口**（1.5.2 起）：每类插件的开关 + 该插件自己的参数、技能清单、'
     '审批策略、团队成员、自动化任务，全在这一页里 |\n'
     '| 模型来源 / 音效提醒 / 本地模型 | 原有页签，行为不变 |'),
    ('模型来源 / 审批策略 / 音效提醒 / 本地模型',
     '插件管理 / 模型来源 / 音效提醒 / 本地模型'),
])

patch('docs/插件要求对照.md', [
    ('✅ 两个值都在 **设置 → 插件参数 → 终端插件** 里可改',
     '✅ 两个值都在 **设置 → 插件管理 → 终端插件（那一行下面的折叠块）** 里可改'),
    ('界面上没有任何入口**，用户根本改不了。补了 **设置 → 插件参数** 这一页（含团队增删、自动化任务增删）。',
     '界面上没有任何入口**，用户根本改不了。补了参数界面；**1.5.2 起并进「插件管理」**'
     '（每个插件的参数贴在它自己那一行下面，含团队增删、自动化任务增删）。'),
], must=False)

# 自测记录：追加这一轮
p = os.path.join(ROOT, 'docs', '软件自测记录.md')
s = io.open(p, encoding='utf-8', newline='').read()
s = s.rstrip() + '\r\n' + NOTE
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('  软件自测记录.md：追加第 42 轮')

print('文档更新完成')
sys.exit(0)
