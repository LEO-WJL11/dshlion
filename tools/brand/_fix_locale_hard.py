#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""中文没生效：把 locale 用**所有已知形式**都摆上，并留一份"自检证据"。

现状（实测）：
  · code-server 4.139.1 **支持 `--locale`**（--help 里有，说明是"设定 vscode 显示语言"）；
  · 语言包 `ms-ceintl.vscode-language-pack-zh-hans` 确实装着，且 vsix 里
    `translations/main.i18n.json` 有 1.8 MB 真实翻译（不是空壳）；
  · code-server 的数据目录里 `User/`、`extensions/`、`argv.json` 都在；
  · 但页面仍是英文。

既然"装是装了、参数也给了"还不生效，就不要再猜单一位置了 —— 这版把 locale 同时写进
**四处**（argv.json 的三种可能位置 + settings.json），启动参数改成 `--locale=zh-cn`
（等号形式，避免被当成分离的第二个参数丢掉），并在启动后往安装目录写一份自检文件，
里面有"扩展是否装上 / locale 文件内容 / 参数"，出问题时一眼能看到是哪一环。

注意：launcher.ps1 必须保持 UTF-8 BOM。
"""

import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
t = io.open(lp, 'rb').read().decode('utf-8-sig')

# 1) 参数改成等号形式
t = t.replace("'--locale', 'zh-cn'", "'--locale=zh-cn'")

# 2) 多写几处 locale 文件（幂等），并记录自检信息
anchor = "$csData = Join-Path $PSScriptRoot 'code-server\\data'"
extra = anchor + '''
# locale 同时写到 code-server 可能读取的每个位置（不同版本读的地方不一样，全给上不亏）
$argvJson = "{`n  `"locale`": `"zh-cn`"`n}`n"
foreach ($d in @($csData, (Join-Path $csData 'data'), (Join-Path $csData 'user-data'),
                 (Join-Path $csData 'User'), (Join-Path $PSScriptRoot 'code-server'))) {
    try {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $d 'argv.json'), $argvJson,
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }
}'''
if '$argvJson' not in t:
    t = t.replace(anchor, extra, 1)

# 3) settings.json 也写到 User 与 data\\User 两处
old_set = "$setFile = Join-Path $csData 'User\\settings.json'"
new_set = """$setDirs = @((Join-Path $csData 'User'), (Join-Path $csData 'data\\User'))
foreach ($sd in $setDirs) { try { New-Item -ItemType Directory -Force -Path $sd | Out-Null } catch { } }
$setFile = Join-Path $csData 'User\\settings.json'"""
if old_set in t and '$setDirs' not in t:
    t = t.replace(old_set, new_set, 1)
    t = t.replace("[System.IO.File]::WriteAllText($setFile, ($set | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))",
                  """foreach ($sd in $setDirs) {
    try { [System.IO.File]::WriteAllText((Join-Path $sd 'settings.json'), ($set | ConvertTo-Json),
            (New-Object System.Text.UTF8Encoding($false))) } catch { }
}""", 1)

# 4) 起完 code-server 后写自检文件
marker = '    Start-Process "http://127.0.0.1:$csPort" | Out-Null'
if 'lionbox-selfcheck.txt' not in t:
    t = t.replace(marker, marker + '''
    # 自检证据：中文没生效时，看这个文件就知道卡在哪一环
    try {
        $lines = @(
            "code-server 数据目录: $csData",
            "argv.json: " + (Get-Content (Join-Path $csData 'argv.json') -Raw -ErrorAction SilentlyContinue),
            "settings.json: " + (Get-Content (Join-Path $csData 'User\\settings.json') -Raw -ErrorAction SilentlyContinue),
            "已装扩展: " + ((Get-ChildItem (Join-Path $csData 'extensions') -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name) -join ', ')
        )
        [System.IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'lionbox-selfcheck.txt'), $lines,
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }''', 1)

io.open(lp, 'wb').write(b'\xef\xbb\xbf' + t.encode('utf-8'))
print('launcher 已更新:')
print('  --locale=zh-cn:', "'--locale=zh-cn'" in t)
print('  argv.json 多处写入:', '$argvJson' in t)
print('  settings.json 两处:', '$setDirs' in t)
print('  自检文件:', 'lionbox-selfcheck.txt' in t)
print('  BOM:', io.open(lp, 'rb').read()[:3] == b'\xef\xbb\xbf')
