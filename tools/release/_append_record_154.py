#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 docs/软件自测记录.md 追加第 44 轮（合成一个包）。"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
P = os.path.join(ROOT, 'docs', '软件自测记录.md')

NOTE = '''
## 2026-10-01 · 第 44 轮：合成一个包（LionBox + VS Code 插件），其他版本下线

### 156. 用户的要求

> "就把它跟 VS Code 的打成一个包，然后原来其他的版本都不要了。"

于是交付物从四个变成一个：`LionBox-Setup-1.5.4.exe` —— 后端 + WebUI + 运行时 + 技能 + **VS Code 插件**。
桌面版（Tauri 套壳）和 JetBrains 插件两个版本下线，源码一起删（需要时 git 历史里还在）。

### 157. 装完自动装插件

安装脚本的 [Run] 调一个纯 ASCII 的批处理 `install-vscode-ext.bat`：自己找 `code` 命令行
（PATH → 官方默认位置 → Insiders → Cursor）装上；找不到就写一句说明到
`{app}\\vscode-extension\\install-result.txt`，**绝不让安装失败**。卸载时同样把插件卸掉。

### 158. 验到什么程度（13 条断言，全过）

`tools/release/_verify_154_single.py`：release 目录里只有一个 exe；装到临时目录 →
**用 `code --list-extensions` 确认插件真的进了 VS Code（6 → 7）** → 用装出来的自带 JRE 起服务
（页面/接口都对）→ 卸载 → **插件也跟着消失（7 → 6）**、目录清空。

### 159. 这一轮抓出来的四个真问题（都是"真装一遍"才暴露的）

1. **vsix 结构不对**：手打的 zip 把文件放在根目录，VS Code 直接报
   `extension/package.json not found inside zip` —— vsix 必须把扩展放在 `extension/` 下，
   还要 `extension.vsixmanifest` 和 `[Content_Types].xml`。1.5.2~1.5.3 单独发的 vsix 都是错的，
   只是没人装过所以没暴露。现在有专门的 `_build_vsix.py`，打完自检这三样。
2. **Inno 的 [Run] 不能直接跑 .bat**：CreateProcess 起不来批处理，静默安装下**连报错都看不见**。
   必须 `{cmd}` + `/c`。第一版就是"装完了但插件没装上、什么都不提示"。
3. **批处理里提前 goto 跳过了找 code 那一步**：卸载时 `CODE` 是空的，于是卸载**静默什么都不做**，
   插件留在 VS Code 里。改成先在 [UninstallRun] 里用 `call` 调用，才从日志里看到这一步没生效。
4. **卸载残留一个空壳目录**：安装脚本写的 `install-result.txt` 不是 Inno 装的文件，
   会把 `vscode-extension` 目录撑住；加 [UninstallDelete] 显式删掉。

### 160. 顺带修的两件事

- 批处理必须**纯 ASCII**：cmd 按 OEM 码页（本机 GBK）读 .bat，UTF-8 的中文注释会被当成命令执行
  （报 `is not recognized as an internal or external command`）。
- 同步脚本加了通用规则：公开树里有、开发仓库里已经没有的文件**一律删掉**。
  以前是手写 REMOVED 名单，每删一批源码都要回去补，漏一次公开仓库就永久留着废弃文件。
'''

s = io.open(P, encoding='utf-8', newline='').read()
if '第 44 轮' in s:
    print('已有第 44 轮，跳过')
    sys.exit(0)
nl = '\r\n' if '\r\n' in s else '\n'
io.open(P, 'w', encoding='utf-8', newline='').write(s.rstrip() + nl + NOTE.replace('\n', nl))
print('软件自测记录已追加第 44 轮')
