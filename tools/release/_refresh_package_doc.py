#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 1.5.2 四个包的实际体积和 sha256 重写 docs/安装包清单.md 的表。

【为什么要脚本算】手抄 sha256 最容易出错（抄错一位就没人能验了），而且每次重出包都会变。
这里直接读文件算，顺带把"体积"也换成实测值。
"""

import hashlib
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join(ROOT, 'installer', 'release')
DOC = os.path.join(ROOT, 'docs', '安装包清单.md')
VER = sys.argv[1] if len(sys.argv) > 1 else '1.5.3'

FILES = [
    ('LionBox-Setup-%s.exe' % VER, 'WebUI 版主程序：后端 + WebUI + 本地模型运行时 + 内置技能，装完从开始菜单/桌面启动（浏览器打开界面）'),
    ('LionBox-Desktop-%s-Setup.exe' % VER, '桌面版：Tauri 套壳同一个 WebUI（界面用系统自带 WebView2 渲染，包里不带浏览器）'),
    ('LionBox-VSCode-%s.vsix' % VER, 'VS Code 扩展：命令面板 → `LionBox: 打开 LionBox Web UI`（内嵌 http://127.0.0.1:8080）'),
    ('LionBox-JetBrains-%s.zip' % VER, 'JetBrains 插件（构建号区间 261+）：Settings → Plugins → ⚙ → Install Plugin from Disk'),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest().upper()


def size_str(n):
    if n < 1024 * 1024:
        return '%.1f KB' % (n / 1024.0)
    return '%.2f MB' % (n / 1048576.0)


def main():
    rows = []
    print('四个包：')
    for name, desc in FILES:
        p = os.path.join(REL, name)
        if not os.path.isfile(p):
            print('  ! 不在：%s' % name)
            return 1
        n = os.path.getsize(p)
        h = sha256(p)
        print('  %-46s %10s  %s' % (name, size_str(n), h))
        rows.append('| `%s` | %s（%s 字节） | `%s` | %s |'
                    % (name, size_str(n), format(n, ','), h, desc))

    s = io.open(DOC, encoding='utf-8', newline='').read()
    head = '| 文件 | 体积 | sha256 | 怎么用 |\n| --- | --- | --- | --- |\n'
    start = s.find('| 文件 | 体积 | sha256 | 怎么用 |')
    if start < 0:
        print('文档里的表头没找到')
        return 1
    end = s.find('\n\n', start)
    if end < 0:
        print('表格结尾没找到')
        return 1
    s = s[:start] + head + '\n'.join(rows) + s[end:]

    # 桌面版那段"sha256 现算"的旧说明删掉（现在表里就有）
    s = s.replace('（桌面版 sha256 用 `certutil -hashfile LionBox-Desktop-%s-Setup.exe SHA256` 现算，切包后会变，\n'
                  '所以不写死在这里；`tools/release/_verify_desktop_install.py` 每次验包都会重新走一遍装/跑/卸。）' % VER,
                  '（表里的 sha256 是当前这四个包的实测值；重出包会变，重算一次即可。\n'
                  '`tools/release/_verify_152_ui.py` 验主包、`tools/release/_verify_desktop_install.py` 验桌面版。）')

    io.open(DOC, 'w', encoding='utf-8', newline='').write(s)
    print('已写入 docs/安装包清单.md')
    return 0


if __name__ == '__main__':
    sys.exit(main())
