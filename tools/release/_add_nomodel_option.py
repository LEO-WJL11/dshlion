#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""加安装选项：**不下载本地模型**（用户明确要求，省 8.9 GB）。

背景：模型权重本来就不在安装包里，是首次用到本地模型时才从 ModelScope 下。
所以这个选项控制的是"要不要自动下"，勾了就永远不自动下（改用云端 API，
或用户自己把 .gguf 放进安装目录）。

三处改动：
  1. installer/LionBox.iss
       [Tasks]  加一个复选框（默认不勾）：nomodel
       [INI]    勾了才写 {app}\lionbox-options.ini → [runtime] autoDownload=0
                （不用 [Code]，避免"任何 [Code] 异常都让静默安装退出 1"那个老坑）
  2. dist/launcher.ps1：读这个 ini，若 autoDownload=0 就给后端加
       --lionbox.runtime.auto-download=false
     并在启动信息里打印一行"本地模型：已关闭自动下载"。
     （用命令行参数而不是环境变量：Spring 的宽松绑定对 auto-download 这种带连字符的键
       容易歧义，命令行参数是确定的。）
  3. 保留 UTF-8 BOM。
"""

import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ---------- 1) iss ----------
p = os.path.join(ROOT, 'installer', 'LionBox.iss')
s = io.open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
b = s.replace('\r\n', '\n') if crlf else s
if 'nomodel' not in b:
    task = ('[Tasks]\n'
            '; 勾上就不自动下载本地模型（8.9 GB），改用云端 API，或以后自己把 .gguf 放进安装目录\n'
            'Name: "nomodel"; Description: "不下载本地模型（省约 8.9 GB；改用云端 API，或以后自己放权重）"; '
            'GroupDescription: "附加选项:"; Flags: unchecked\n\n'
            '[INI]\n'
            '; 勾了 nomodel 才写这个文件；launcher.ps1 读它决定要不要给后端加 auto-download=false\n'
            'Filename: "{app}\\lionbox-options.ini"; Section: "runtime"; Key: "autoDownload"; '
            'String: "0"; Tasks: nomodel\n\n'
            '[Icons]')
    b = b.replace('[Icons]', task, 1)
    if crlf:
        b = b.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(b)
    print('iss: 已加 nomodel 复选框 + [INI]')
else:
    print('iss: nomodel 已有')

# ---------- 2) launcher ----------
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
t = io.open(lp, 'rb').read().decode('utf-8-sig')
if 'auto-download=false' not in t:
    # a) 读选项
    anchor = "$jarPath = Join-Path $Root $JarName"
    read_opt = '''$runtimeArgs = @()
# 安装时勾了"不下载本地模型"就写在这个文件里（见 installer/LionBox.iss 的 [INI]）
$optFile = Join-Path $Root 'lionbox-options.ini'
if (Test-Path $optFile) {
    $optTxt = Get-Content $optFile -Raw -ErrorAction SilentlyContinue
    if ($optTxt -match 'autoDownload\\s*=\\s*0') {
        $runtimeArgs += '--lionbox.runtime.auto-download=false'
    }
}
''' + anchor
    t = t.replace(anchor, read_opt, 1)
    # b) 传参
    t = t.replace("""        '-jar', $jarPath
    )""", """        '-jar', $jarPath
    ) + $runtimeArgs""", 1)
    # c) 打印一行
    t = t.replace("""    Write-Line '  ============================================'""",
                  """    if ($runtimeArgs.Count -gt 0) {
        Write-Line '     本地模型  : 已关闭自动下载（安装时选的；要用本地模型就把 .gguf 放进安装目录）'
    }
    Write-Line '  ============================================''""", 1)
    io.open(lp, 'wb').write(b'\xef\xbb\xbf' + t.encode('utf-8'))
    print('launcher: 已读选项并传 --lionbox.runtime.auto-download=false')
else:
    print('launcher: 已有')

print('iss 有 nomodel:', 'nomodel' in io.open(p, encoding='utf-8-sig').read())
print('launcher BOM:', io.open(lp, 'rb').read()[:3] == b'\xef\xbb\xbf')
