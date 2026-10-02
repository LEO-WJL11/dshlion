#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""每次出包前把版本号 +1（1.5.4 → 1.5.5 → …），一处改、处处一致。

【为什么要这样】同一个版本号出两个不同的包，用户根本分不清装的是哪个 ——
刚才就发生过：315.6 MB 的原生 VS Code 版和 244.7 MB 的 code-server 版都叫 1.5.4。
从现在起**每次编译都换号**：文件名就是版本，安装记录、exe 属性、插件里也是同一个号。

版本号的"唯一来源"是 installer/LionBox.iss 里的 `#define AppVersion "x.y.z"`，
其余地方（VS Code 插件清单、桌面工程若存在）从这里同步，避免各写各的。
用法：python tools/release/_bump_version.py     （出包前先跑它）
"""

import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ISS = os.path.join(ROOT, 'installer', 'LionBox.iss')


def read_version():
    s = io.open(ISS, encoding='utf-8', newline='').read()
    m = re.search(r'#define\s+AppVersion\s+"(\d+)\.(\d+)\.(\d+)"', s)
    if not m:
        raise SystemExit('在 installer/LionBox.iss 里找不到 #define AppVersion "x.y.z"')
    return s, int(m.group(1)), int(m.group(2)), int(m.group(3))


def main():
    s, major, minor, patch = read_version()
    old = '%d.%d.%d' % (major, minor, patch)
    new = '%d.%d.%d' % (major, minor, patch + 1)

    # 改唯一来源
    s = s.replace('#define AppVersion     "%s"' % old, '#define AppVersion     "%s"' % new, 1)
    if old not in s and new not in s:
        raise SystemExit('iss 里的 AppVersion 行格式变了，先看一眼')
    io.open(ISS, 'w', encoding='utf-8', newline='').write(s)

    # 同步到 VS Code 插件清单（有就同步，没有就跳过）
    changed = ['installer/LionBox.iss']
    vs = os.path.join(ROOT, 'extensions', 'vscode', 'package.json')
    if os.path.isfile(vs):
        raw = io.open(vs, 'rb').read()
        if raw[:3] == b'\xef\xbb\xbf':
            raise SystemExit('extensions/vscode/package.json 有 BOM，先去掉再同步版本号')
        d = json.loads(raw.decode('utf-8'))
        d['version'] = new
        io.open(vs, 'w', encoding='utf-8', newline='').write(
            json.dumps(d, ensure_ascii=False, indent=2) + '\n')
        changed.append('extensions/vscode/package.json')

    print('%s → %s' % (old, new))
    print('已同步：' + '、'.join(changed))
    print('（出包后文件名就是 LionBox-Setup-%s.exe）' % new)
    return 0


if __name__ == '__main__':
    sys.exit(main())
