#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把新图标接到三个地方：安装包、VS Code 插件、网页界面。

用户要的图标 = 一个盒子 + 盒口探出的狮子头，已由 tools/brand/_make_icon.py 生成。
这里只做接线，不改图形。注意：
  · .iss 里 [Icons] 每行都要带 IconFilename，否则开始菜单还是默认图标；
  · 插件的活动栏图标必须是**单色描边**（VS Code 会自己上色），所以用 lionbox-mono.svg；
  · 网页用彩色 PNG 做 favicon/logo，从 web/ 目录直接提供（后端就是从这里发的）。
"""

import io, os, re, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ICON = os.path.join(ROOT, 'assets', 'icon')
ICO = os.path.join(ICON, 'lionbox.ico')
MONO = os.path.join(ICON, 'lionbox-mono.svg')
PNG256 = os.path.join(ICON, 'lionbox-256.png')
PNG128 = os.path.join(ICON, 'lionbox-128.png')

# ---------- 1) 安装包 ----------
iss = os.path.join(ROOT, 'installer', 'LionBox.iss')
s = io.open(iss, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
b = s.replace('\r\n', '\n') if crlf else s
changed = []

if 'SetupIconFile' not in b:
    b = b.replace('[Setup]', '[Setup]\nSetupIconFile=..\\assets\\icon\\lionbox.ico', 1)
    changed.append('SetupIconFile')

if 'lionbox.ico"; DestDir' not in b:
    anchor = '[Icons]'
    add = ('[Files]\n'
           '; 应用图标：开始菜单/桌面快捷方式和卸载项都用它\n'
           'Source: "..\\assets\\icon\\lionbox.ico"; DestDir: "{app}"; Flags: ignoreversion\n\n')
    b = b.replace(anchor, add + anchor, 1)
    changed.append('[Files] 图标')

def add_icon_filename(text):
    """只给 [Icons] 段里的 Name: 行加 IconFilename。

    【为什么要判段】最早按"行首是 Name:"就加，结果 [Languages] 那行也被加了，
    ISCC 直接报 `Unrecognized parameter name "IconFilename"` —— 而且每次重跑这个
    脚本都会再犯一次。所以这里必须跟踪当前处在哪个段。
    """
    out, section = [], ''
    for line in text.split('\n'):
        st = line.strip()
        if st.startswith('[') and st.endswith(']'):
            section = st
        if section == '[Icons]' and st.startswith('Name:') and 'IconFilename' not in line:
            line = line.rstrip() + '; IconFilename: "{app}\\lionbox.ico"'
        out.append(line)
    return '\n'.join(out)

b = add_icon_filename(b)
if crlf:
    b = b.replace('\n', '\r\n')
io.open(iss, 'w', encoding='utf-8', newline='').write(b)
print('iss: ' + ('、'.join(changed) if changed else '已是最新'))

# ---------- 2) VS Code 插件 ----------
media = os.path.join(ROOT, 'extensions', 'vscode', 'media')
os.makedirs(media, exist_ok=True)
shutil.copyfile(MONO, os.path.join(media, 'lionbox.svg'))     # 活动栏（单色）
shutil.copyfile(PNG128, os.path.join(media, 'icon.png'))      # 插件列表用（彩色）
pkg = os.path.join(ROOT, 'extensions', 'vscode', 'package.json')
d = io.open(pkg, encoding='utf-8').read()
if '"icon"' not in d:
    d = d.replace('"version":', '"icon": "media/icon.png",\n  "version":', 1)
    io.open(pkg, 'w', encoding='utf-8', newline='').write(d)
    print('package.json: 已加 icon 字段')
else:
    print('package.json: icon 字段已有')
print('插件图标: media/lionbox.svg（单色）、media/icon.png（彩色）')

# ---------- 3) 网页 ----------
shutil.copyfile(ICO, os.path.join(ROOT, 'web', 'favicon.ico'))
shutil.copyfile(PNG256, os.path.join(ROOT, 'web', 'lionbox.png'))
html = os.path.join(ROOT, 'web', 'index.html')
h = io.open(html, encoding='utf-8', newline='').read()
hc = '\r\n' in h
hb = h.replace('\r\n', '\n') if hc else h
if 'favicon.ico' not in hb:
    hb = hb.replace('<meta charset="utf-8">',
                    '<meta charset="utf-8">\n    <link rel="icon" href="/favicon.ico">\n'
                    '    <link rel="apple-touch-icon" href="/lionbox.png">', 1)
if 'logo-icon">🦁' in hb:
    hb = hb.replace('<span class="logo-icon">🦁</span>',
                    '<img class="logo-icon" src="/lionbox.png" alt="LionBox" '
                    'style="width:22px;height:22px;border-radius:6px;vertical-align:-5px">')
if hc:
    hb = hb.replace('\n', '\r\n')
io.open(html, 'w', encoding='utf-8', newline='').write(hb)
print('web: favicon.ico + lionbox.png + 顶栏 logo 已换成新图标')
