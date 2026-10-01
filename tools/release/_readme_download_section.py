#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 README 补上"去哪下载、下完怎么用"。

用户的原话："你仓库里面得写告诉用户怎么使用，在这个仓库的哪个文件夹里下载下去怎么用。
你不说我也不会。"

确实是漏的：README 里只写了"就一个包 LionBox-Setup-1.5.4.exe"，但**没说它在仓库哪个文件夹**、
怎么点下载、下完第一步做什么。对第一次来的人（包括用户自己）这就是死路。

补两节，都放在最前面：
  ① 下载与安装（三分钟）：仓库路径 + 直链 + 双击装 + 首次下权重 + 在 VS Code 里怎么开始 + 怎么卸；
  ② 这个仓库里东西都在哪：installer/release 是安装包、skills 是技能、extensions 是插件源码、
     tools/checks 是用例、docs 是文档 —— 一眼知道该点哪个文件夹。
另外把模型 README 里"仓库 installer/release/"改成完整链接。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = '1.5.4'
REPO = 'https://github.com/LEO-WJL11/dshlion'

DOWNLOAD = '''## ⬇️ 下载与安装（三分钟）

**去哪里下**：这个仓库的 **`installer/release/`** 文件夹里，就一个文件：

> ### 👉 [`installer/release/LionBox-Setup-{ver}.exe`]({repo}/blob/main/installer/release/LionBox-Setup-{ver}.exe)

打开那个页面后点右上角的 **Download**（或直接点这个直链：
[{ver} 直链下载]({repo}/raw/main/installer/release/LionBox-Setup-{ver}.exe)），
大小 **74 MB**。仓库里没有别的东西需要下 —— 模型权重不在这里，首次使用时自动下（见下）。

**怎么装**：

1. 双击 `LionBox-Setup-{ver}.exe`；
2. 如果 Windows 弹出蓝色的"已保护你的电脑"（SmartScreen）：点 **更多信息 → 仍要运行**
   —— 安装包没有买代码签名，这一步是正常的，不是有毒；
3. 一路下一步（**不需要管理员权限**，装到当前用户目录）；
4. 安装快结束时它会**自动把 VS Code 插件也装上**（自己找 `code` 命令；
   找不到就跳过，不影响本体使用）。

**装完怎么开始用**：

| 你想在哪用 | 怎么做 |
| --- | --- |
| **在 VS Code 里**（推荐） | 打开 VS Code → 打开你的项目文件夹 → **最右边那一栏**就是「LionBox Agent」，直接在里面说话就行（第一次可能要把 VS Code 重开一下让插件生效） |
| 在浏览器里 | 开始菜单启动 LionBox（或桌面快捷方式），然后开 `http://127.0.0.1:8080` |

**第一次会先下模型**：如果安装目录里还没有权重，助手会自动从 ModelScope 下载
`lion-merged-Q8_0.gguf`（**8.87 GB，只下一次**，界面上能看到进度）。
网慢就先干别的，下完再聊；想离线部署就把 `.gguf` 手动放进安装目录，程序优先用本地文件。

**卸载**：Windows 设置 → 应用 → LionBox 卸载（或开始菜单里的卸载项）。
VS Code 插件会一起卸掉；你的会话和工作区数据保留。

---

## 📁 这个仓库里东西都在哪

| 想看/想要的 | 去这个文件夹 |
| --- | --- |
| **安装包**（用户只要这个） | **`installer/release/`** —— `LionBox-Setup-{ver}.exe` |
| 内置技能（通用 skill 格式，可以自己加） | `skills/` —— 每个子目录一个技能（有 `SKILL.md`） |
| VS Code 插件源码 | `extensions/vscode/`（`README.md` 讲它怎么工作） |
| 后端源码 | `src/main/java/com/lioncode/` |
| 前端（单文件，无构建步骤） | `web/index.html` |
| 安装脚本 | `installer/LionBox.iss`、`installer/install-vscode-ext.bat` |
| 回归用例（39 个套件） | `tools/checks/`（`python tools/checks/_run_all_checks.py` 一把跑） |
| 出包脚本 | `tools/release/` |
| 文档 | `docs/`（安装包清单、插件系统、上下文与改动审核、界面说明……） |

模型权重**不在这个仓库**（太大），在 ModelScope：
**[lionnezha/lion-models](https://modelscope.cn/models/lionnezha/lion-models)**。

'''

BLOCK = '## 装什么（就一个包）'
MARKER = '## ⬇️ 下载与安装'


def patch(path, pairs):
    p = os.path.join(ROOT, path)
    if not os.path.isfile(p):
        print('跳过（不存在）：%s' % path)
        return False
    s = io.open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    if crlf:
        s = s.replace('\r\n', '\n')
    n = 0
    for old, new in pairs:
        if old in s:
            s = s.replace(old, new)
            n += 1
    if crlf:
        s = s.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('%s：替换 %d 处' % (path, n))
    return True


def main():
    p = os.path.join(ROOT, 'README.md')
    s = io.open(p, encoding='utf-8', newline='').read()
    if MARKER in s:
        print('README.md 已有下载章节，跳过')
        return 0
    crlf = '\r\n' in s
    body = s.replace('\r\n', '\n') if crlf else s
    if BLOCK not in body:
        print('! README.md 里找不到「装什么（就一个包）」这一节')
        return 1
    insert = DOWNLOAD.format(ver=VER, repo=REPO)
    body = body.replace(BLOCK, insert + BLOCK, 1)
    # "装什么"那一节里也把路径写清楚
    body = body.replace(
        '`LionBox-Setup-%s.exe`（74 MB）= 后端 + WebUI + llama.cpp 运行时（Vulkan）+ 精简 JRE +' % VER,
        '**`installer/release/LionBox-Setup-%s.exe`**（74 MB）= 后端 + WebUI + llama.cpp 运行时（Vulkan）+ 精简 JRE +' % VER,
        1)
    if crlf:
        body = body.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(body)
    print('README.md：已插入「下载与安装（三分钟）」与「这个仓库里东西都在哪」')

    # 模型 README：把"仓库 installer/release/"写成完整路径 + 直链
    for rel in ('README-模型.md', 'dist/README-模型.md'):
        patch(rel, [
            ('**想开箱即用**：下载 `LionBox-Setup-*.exe`（仓库 `installer/release/` 或 Releases），',
             '**想开箱即用**：下载 `LionBox-Setup-*.exe` —— 在 LionBox 仓库的\n'
             '**`installer/release/`** 文件夹里（[直接打开](' + REPO + '/tree/main/installer/release)），'),
        ])
    return 0


if __name__ == '__main__':
    sys.exit(main())
