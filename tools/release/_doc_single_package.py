#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 docs/安装包清单.md 改成"只有一个包"（1.5.4）。

用户的话："就把它跟 VS Code 的打成一个包，然后原来其他的版本都不要了。"
所以这份文档不能再写四个包 —— 文档跟现实不一致，比没有文档更糟。
"""

import hashlib
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join(ROOT, 'installer', 'release')
DOC = os.path.join(ROOT, 'docs', '安装包清单.md')
VER = '1.5.4'


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for c in iter(lambda: f.read(1 << 20), b''):
            h.update(c)
    return h.hexdigest().upper()


TEXT = '''# 安装包（1.5.4 起：只有一个包）

| 文件 | 体积 | sha256 |
| --- | --- | --- |
| `LionBox-Setup-{ver}.exe` | {size} | `{sha}` |

**里面装了什么**：后端 + WebUI + 本地模型运行时（llama.cpp / Vulkan）+ 自带精简 JRE +
内置技能 + **VS Code 插件**。装完打开 VS Code，最右边那一栏就是 LionBox Agent 面板。

## 一套包，怎么用

1. 双击 `LionBox-Setup-{ver}.exe` 安装（当前用户，不需要管理员）；
2. 安装过程会**自动把 VS Code 插件装上**（自己找 `code` 命令行；找不到就跳过，
   插件包留在 `{app}\\vscode-extension\\LionBox-VSCode.vsix`，可在 VS Code 里"从 VSIX 安装"）。
   装没装成功看 `{app}\\vscode-extension\\install-result.txt`；
3. 从开始菜单启动 LionBox（或桌面快捷方式），首次使用会自动下载模型权重（约 8.9GB）；
4. VS Code 里打开你的项目文件夹 → 右侧栏 LionBox Agent：工作区就是打开的文件夹，
   `@ 文件` 只把那一个文件读进上下文，AI 改的文件要先点「通过」才落盘。
5. 卸载时**VS Code 插件会一起卸掉**（不留一个连不上后端的空面板）。

## 为什么只剩一个包

| 以前的版本 | 现在 |
| --- | --- |
| `LionBox-Setup-*.exe`（主程序） | **保留**，并且把 VS Code 插件打进去了 |
| `LionBox-Desktop-*-Setup.exe`（Tauri 套壳桌面版） | 去掉：客户端就是 VS Code 右侧栏，不再另做一个桌面壳 |
| `LionBox-VSCode-*.vsix`（单独发） | 去掉单独发布，改成随安装包装上 |
| `LionBox-JetBrains-*.zip`（JetBrains 插件） | 去掉：先只做 VS Code |

被去掉的那两个版本的源码也一起删了（桌面版 `desktop/tauri`、JetBrains 插件 `extensions/jetbrains`）——
需要的时候在 git 历史里还在。

## 已知限制

- 没有代码签名：Windows SmartScreen 首次运行会拦一下（"更多信息 → 仍要运行"）。
- 自动装插件认这些位置：PATH 里的 `code`、`%LOCALAPPDATA%\\Programs\\Microsoft VS Code\\bin\\code.cmd`、
  `%ProgramFiles%\\Microsoft VS Code\\bin\\code.cmd`、VS Code Insiders、Cursor。
  装在别处的话手动装一下 VSIX（文件就在安装目录里）。
- 模型权重不随包发布（太大），首次使用自动从 ModelScope 下载；想离线就先把
  `lion-merged-Q8_0.gguf` 放到 `{app}\\` 下面。
- 上下文窗口默认 16K（省预填充），Agent 自己能用 `context_window` 工具临时调大、干完调回来；
  细节见 [上下文与改动审核.md](上下文与改动审核.md)。
'''


def main():
    setup = os.path.join(REL, 'LionBox-Setup-%s.exe' % VER)
    if not os.path.isfile(setup):
        print('没找到 %s' % setup)
        return 1
    files = sorted(os.listdir(REL))
    print('release 目录：%s' % ', '.join(files))
    # 【别用 str.format】文档里满是 {app} 这种字面量，format 会把它们当占位符（报 KeyError: 'app'）。
    # 只替换我们自己那个 {ver} 占位符，其余花括号原样保留。
    text = (TEXT
            .replace('{ver}', VER)
            .replace('{size}', '%.2f MB（%s 字节）' % (os.path.getsize(setup) / 1048576.0,
                                                      format(os.path.getsize(setup), ',')))
            .replace('{sha}', sha256(setup)))
    io.open(DOC, 'w', encoding='utf-8', newline='').write(text.replace('\n', '\r\n'))
    print('已重写 docs/安装包清单.md（一个包）')
    print('sha256 %s' % sha256(setup))
    return 0


if __name__ == '__main__':
    sys.exit(main())
