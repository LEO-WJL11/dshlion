#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把简体中文语言包一起打进安装包，并让启动就弹面板、走简体。"""

import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 1) iss：打语言包
p = os.path.join(ROOT, 'installer', 'LionBox.iss')
s = io.open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
b = s.replace('\r\n', '\n') if crlf else s
if 'dist\\vscode-ext' not in b:
    anchor = 'Source: "..\\dist\\vscode\\*"'
    add = ('; 简体中文语言包（zh-HANS，不是繁体）：装进自带 VS Code，界面直接是中文\n'
           'Source: "..\\dist\\vscode-ext\\*"; DestDir: "{app}\\vscode-ext"; Flags: ignoreversion\n')
    b = b.replace(anchor, add + anchor, 1)
    if crlf: b = b.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(b)
    print('iss: 已加语言包')
else:
    print('iss: 语言包已有')

# 2) 装插件脚本：装完自己的插件再装语言包
bp = os.path.join(ROOT, 'installer', 'install-vscode-ext.bat')
t = io.open(bp, encoding='ascii', newline='').read().replace('\r\n', '\n')
if 'zh-hans' not in t:
    t = t.replace('set VSIX=%HERE%vscode-extension\\LionBox-VSCode.vsix',
                  'set VSIX=%HERE%vscode-extension\\LionBox-VSCode.vsix\nset LANGPACK=%HERE%vscode-ext\\zh-hans.vsix', 1)
    t = t.replace('if not exist "%VSIX%" ( echo',
                  'if exist "%LANGPACK%" (\n'
                  '  echo [%DATE% %TIME%] Installing Simplified Chinese language pack ...>> "%RESULT%"\n'
                  '  call "%BUNDLED%" --install-extension "%LANGPACK%" --force --extensions-dir "%DATAEXT%" --user-data-dir "%DATAUSER%" >> "%RESULT%" 2>&1\n'
                  ')\n'
                  'if not exist "%VSIX%" ( echo', 1)
    io.open(bp, 'w', encoding='ascii', newline='\r\n').write(t)
    print('bat: 已加语言包安装')
else:
    print('bat: 已有')

# 3) launcher：简体 + 面板自动弹
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
raw = io.open(lp, 'rb').read()
txt = raw.decode('utf-8-sig')
if '--locale=zh-cn' not in txt:
    txt = txt.replace("'--new-window',", "'--new-window', '--locale=zh-cn',", 1)
    io.open(lp, 'wb').write(b'\xef\xbb\xbf' + txt.encode('utf-8'))
    print('launcher: 已加 --locale=zh-cn')
else:
    print('launcher: 已有')
