#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把自带 VS Code（dist\vscode）接进安装包：左 VS Code、右我们的 Agent。

用户要求：左边原版 VS Code，右边"原来显示插件的那一栏"改成 Agent 面板；装完就能用。

三处改动：
  1. installer/LionBox.iss：把 dist\vscode\* 打进 {app}\vscode（便携模式目录）；
  2. installer/install-vscode-ext.bat：优先给**自带**那份装插件，用
     --extensions-dir/--user-data-dir 指到 {app}\vscode\data 里，不碰用户的 VS Code 配置；
  3. dist/launcher.ps1：优先用 {app}\vscode\Code.exe 打开（带 LIONBOX_MIXED=1 让面板自动弹出），
     找不到才退回系统 VS Code / 浏览器。**必须写 UTF-8 BOM**（5.1 没 BOM 会把中文读坏）。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ---------------- 1) iss ----------------
p = os.path.join(ROOT, 'installer', 'LionBox.iss')
s = io.open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in s
body = s.replace('\r\n', '\n') if crlf else s
if 'dist\\vscode' not in body:
    anchor = 'Source: "安装VS Code插件.bat"' if 'Source: "安装VS Code插件.bat"' in body else 'Source: "install-vscode-ext.bat"'
    add = ('; 自带一份原版 VS Code（便携模式）：装完左边是 VS Code、右边是我们的 Agent 面板，\n'
           '; 干净电脑上不需要预装任何东西。体积大，压缩留到最后统一做。\n'
           'Source: "..\\dist\\vscode\\*"; DestDir: "{app}\\vscode"; Flags: ignoreversion recursesubdirs createallsubdirs\n')
    body = body.replace(anchor, add + anchor, 1)
    if crlf:
        body = body.replace('\n', '\r\n')
    io.open(p, 'w', encoding='utf-8', newline='').write(body)
    print('iss: 已加 dist\\vscode → {app}\\vscode')
else:
    print('iss: 已有')

# ---------------- 2) 插件安装脚本（纯 ASCII） ----------------
BAT = r'''@echo off
rem Install/uninstall the LionBox extension into the BUNDLED VS Code (portable, data\ dir),
rem falling back to a system-wide VS Code if the bundled one is missing.
rem ASCII only: cmd decodes .bat with the OEM code page; UTF-8 Chinese would run as commands.
setlocal
set HERE=%~dp0
set VSIX=%HERE%vscode-extension\LionBox-VSCode.vsix
set MODE=%~1
if "%MODE%"=="" set MODE=install

set BUNDLED=%HERE%vscode\Code.exe
set DATAEXT=%HERE%vscode\data\extensions
set DATAUSER=%HERE%vscode\data\user-data

if /i "%MODE%"=="uninstall" (
  set RESULT=%TEMP%\lionbox-vscode-uninstall.txt
  goto :douninstall
)
set RESULT=%HERE%vscode-extension\install-result.txt
if not exist "%HERE%vscode-extension" mkdir "%HERE%vscode-extension" >nul 2>&1
if not exist "%VSIX%" ( echo [%DATE% %TIME%] VSIX missing: %VSIX%> "%RESULT%" & exit /b 0 )

echo [%DATE% %TIME%] Installing LionBox extension ...> "%RESULT%"
if exist "%BUNDLED%" (
  echo [%DATE% %TIME%] Target: bundled VS Code (portable)>> "%RESULT%"
  call "%BUNDLED%" --install-extension "%VSIX%" --force --extensions-dir "%DATAEXT%" --user-data-dir "%DATAUSER%" >> "%RESULT%" 2>&1
) else (
  set CODE=
  where code >nul 2>&1 && set CODE=code
  if not defined CODE if exist "%LOCALAPPDATA%\Programs\Microsoft VS Code\bin\code.cmd" set CODE=%LOCALAPPDATA%\Programs\Microsoft VS Code\bin\code.cmd
  if not defined CODE if exist "%ProgramFiles%\Microsoft VS Code\bin\code.cmd" set CODE=%ProgramFiles%\Microsoft VS Code\bin\code.cmd
  if not defined CODE ( echo [%DATE% %TIME%] No VS Code found; install the VSIX manually: %VSIX%>> "%RESULT%" & exit /b 0 )
  echo [%DATE% %TIME%] Target: system VS Code>> "%RESULT%"
  call "%CODE%" --install-extension "%VSIX%" --force >> "%RESULT%" 2>&1
)
set RC=%ERRORLEVEL%
echo [%DATE% %TIME%] exit=%RC%>> "%RESULT%"
if "%RC%"=="0" (echo [%DATE% %TIME%] OK: lioncode.lionbox installed.>> "%RESULT%") else (echo [%DATE% %TIME%] FAILED exit=%RC%>> "%RESULT%")
exit /b 0

:douninstall
if exist "%BUNDLED%" (
  call "%BUNDLED%" --uninstall-extension lioncode.lionbox --extensions-dir "%DATAEXT%" --user-data-dir "%DATAUSER%" >> "%RESULT%" 2>&1
  echo [%DATE% %TIME%] removed from bundled VS Code.>> "%RESULT%"
)
exit /b 0
'''
bp = os.path.join(ROOT, 'installer', 'install-vscode-ext.bat')
io.open(bp, 'w', encoding='ascii', newline='\r\n').write(BAT)
print('install-vscode-ext.bat: 已改为优先装进自带 VS Code 的便携 data 目录')

# ---------------- 3) launcher.ps1：优先自带 VS Code ----------------
lp = os.path.join(ROOT, 'dist', 'launcher.ps1')
raw = io.open(lp, 'rb').read()
text = raw.decode('utf-8-sig')
start = text.find('# ---------- 5. 打开界面')
if start < 0:
    print('launcher: 找不到第 5 步')
    sys.exit(1)
NEW = '''# ---------- 5. 打开界面：左边自带 VS Code，右边 LionBox Agent ----------
# 自带一份便携版 VS Code（{app}\\vscode），所以干净电脑上不需要预装任何东西。
# LIONBOX_MIXED=1 让我们的插件激活后自动把 Agent 面板弹出来（占的就是原来"扩展"那一栏）。
$bundled = Join-Path $PSScriptRoot 'vscode\\Code.exe'
if (Test-Path $bundled) {
    Write-Line '     正在打开 VS Code 混合版（左边 VS Code，右边 LionBox Agent）…'
    $env:LIONBOX_MIXED = '1'
    $dataDir = Join-Path $PSScriptRoot 'vscode\\data'
    try {
        Start-Process -FilePath $bundled -ArgumentList @('--new-window',
            "--extensions-dir=$dataDir\\extensions", "--user-data-dir=$dataDir\\user-data") | Out-Null
        Write-Line '     面板没自动出来就按 Ctrl+Shift+P -> LionBox: 打开 Agent 面板（右侧栏）'
    }
    catch {
        Write-Line "     开自带 VS Code 失败（$($_.Exception.Message)），改用浏览器"
        Start-Process "http://127.0.0.1:$AgentPort" | Out-Null
    }
}
else {
    Write-Line '     没找到自带的 VS Code，用浏览器打开界面'
    Start-Process "http://127.0.0.1:$AgentPort" | Out-Null
}
Start-Sleep -Seconds 4
'''
io.open(lp, 'wb').write(b'\xef\xbb\xbf' + (text[:start] + NEW).encode('utf-8'))
print('launcher.ps1: 已改为优先开自带 VS Code（并写回 UTF-8 BOM）')
