#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把文档里关于桌面版的旧说法（Electron / 分片）更新成 Tauri 的说法。

【为什么用脚本改文档】这台机器上 PowerShell 的 Get-Content/Set-Content 按 GBK 读写，
中文会被整段写成乱码（这一轮已经栽过两次：web/index.html 和 package.json）。
改任何带中文的文件一律走 Python，先读字节、改完再写回。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def patch(path, pairs, append=None):
    full = os.path.join(ROOT, path)
    if not os.path.isfile(full):
        print('  跳过（不存在）：%s' % path)
        return False
    s = io.open(full, encoding='utf-8', newline='').read()
    hit = 0
    for old, new in pairs:
        if old in s:
            s = s.replace(old, new)
            hit += 1
    if append:
        s = s.rstrip() + '\r\n' + append
    io.open(full, 'w', encoding='utf-8', newline='').write(s)
    print('  %s：替换 %d 处%s' % (path, hit, '，并追加了一段' if append else ''))
    return True


NOTE = '''
## 2026-10-01 · 第 41 轮：桌面版换 Tauri（178.79 MB → 2.84 MB），不再分片

### 140. 为什么换

用户的要求："不能分片，想办法用更小的方案，但还是套壳、不重新开发客户端"。
查过一圈小体积方案（Tauri / Neutralinojs / Wails / Electrobun，都是"用系统 WebView、
不打包浏览器"这个思路），选了 Tauri 2：文档生态最成熟，而且这台机器上 MSVC 工具链已经就位，
只需要装一个 Rust（约 300 MB）。

结果：桌面版安装包从 **178.79 MB 降到 2.84 MB**，壳本体 3.17 MB。
差的那 170 多 MB 就是 Electron 打包进去的那个完整 Chromium。
**客户端一行没改**：窗口加载的还是后端 `127.0.0.1:8080` 发的那份 WebUI。

| | Electron（旧） | Tauri（现在） |
|---|---|---|
| 安装包 | 178.79 MB（还要切两片才进得了 GitHub） | 2.84 MB（单文件） |
| 渲染引擎 | 自带 Chromium | 系统 WebView2（Win10/11 自带） |
| 进不进得了仓库 | 进不去 | 直接进，两个仓库都有 |

### 141. 验到什么程度（说清楚，不糊）

- 桌面版安装包：装到临时目录 → 用**装出来的那份 exe** 跑自检 → 卸载干净，10 条断言全过
  （`tools/release/_verify_desktop_install.py`）。自检验的是：地址解析、后端探测、
  主程序 jar 与精简 JRE 定位、系统 WebView2 版本。
- **窗口渲染这一步在这个环境里验不了**：WebView2 的宿主与浏览器进程之间走命名管道通信，
  受限的自动化会话里起不来 —— 实测 Tauri 报 `0x8000FFFF`（灾难性故障）、
  手写的 .NET 宿主直接 CLR 崩溃；而同一台机器上 Edge headless 抓页面完全正常。
  所以"窗口里能不能显示界面"要在真实桌面上双击看，这条不能替用户下结论。

### 142. 这一轮踩到并写进注释的坑

1. **Inno 会把字符串里的 `{GUID}` 当常量展开** —— 注册表路径里的花括号必须写 `{{...}}`，
   否则安装程序启动即退出（exit=1、连日志都不写、一个文件都不装，完全不提示原因）。
2. **安装时的 WebView2 检测整个删掉了**：放在 `[Code]` 里一旦有闪失就是这个后果。
   检查挪到应用里（壳启动时发现 WebView2 起不来会在窗口里显示提示 + 官方下载链接），
   安装脚本保持"复制文件 + 快捷方式 + 卸载项"这么简单。
3. **从工作区里跑 Inno 安装包会被这个环境挡住自解压**（同一个 exe 拷到 %TEMP% 跑就正常）。
   验包脚本现在先拷出来再装 —— 之前"装了没反应"折腾半天就是这个原因，不是包坏了。
4. 壳的 `--selftest` 结果**必须写文件**：release 版是 `windows_subsystem="windows"`，
   没有控制台，println! 出来的东西没人接（手动跑看着有输出、脚本里重定向却是空的）。

### 143. 出包 1.5.1（桌面版换形态后重新出）

| 文件 | 体积 |
|---|---|
| LionBox-Setup-1.5.1.exe | 74.21 MB（WebUI 版主程序） |
| LionBox-Desktop-1.5.1-Setup.exe | **2.84 MB**（Tauri 套壳桌面版，单文件） |
| LionBox-VSCode-1.5.1.vsix | 9.7 KB |
| LionBox-JetBrains-1.5.1.zip | 8.4 KB |

四份都是单文件，两个仓库都有；旧的 Electron 源码、切分脚本和两个 .part 分卷已删除。
'''

patch('docs/软件自测记录.md', [
    ('WebUI（就是本体）+ Electron 套壳（desktop/electron，直接加载本机 WebUI，不重做界面）',
     'WebUI（就是本体）+ Tauri 套壳（desktop/tauri，用系统 WebView 加载本机 WebUI，不重做界面；'
     '1.5.1 起从 Electron 换成 Tauri，安装包 178.79 MB → 2.84 MB）'),
], append=NOTE)

patch('extensions/发布形态调研.md', [
    ('> 结论先行：',
     '> **【2026-10-01 更新】桌面版最终选了 Tauri 2（不是 Electron）**：同样是"用系统 WebView、\n'
     '> 不打包浏览器"，安装包从 Electron 的 178.79 MB 降到 2.84 MB。下面的 Electron 调研保留作为\n'
     '> 备选方案的资料，实际交付的是 `desktop/tauri/`（VS Code / JetBrains 两个插件不受影响）。\n'
     '>\n'
     '> 结论先行：'),
])

print('文档更新完成')
sys.exit(0)
