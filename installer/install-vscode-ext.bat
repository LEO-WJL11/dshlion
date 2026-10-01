@echo off
rem ===========================================================================
rem Install / uninstall the LionBox extension for VS Code.
rem Called by the installer, but you can also double-click it.
rem
rem WHY A SEPARATE .BAT (instead of Inno's [Code] section):
rem   Anything that goes wrong inside Inno's [Code] makes a silent install exit
rem   with code 1, write no log, and copy no files at all. Keeping the logic in a
rem   plain batch file means it can be run, read and debugged on its own.
rem
rem WHY THIS FILE IS PURE ASCII:
rem   cmd.exe reads .bat files using the OEM code page (GBK on a Chinese system).
rem   A UTF-8 batch file with Chinese comments gets mis-decoded and the comments
rem   are executed as commands ("'...' is not recognized as an internal command").
rem   ASCII only = no decoding surprises. Chinese text lives in the installer
rem   script and in the app, not here.
rem
rem Usage:
rem   install-vscode-ext.bat             install (never fails; writes a result file)
rem   install-vscode-ext.bat uninstall    uninstall (called by the uninstaller)
rem ===========================================================================
setlocal enabledelayedexpansion
set HERE=%~dp0
set VSIX=%HERE%vscode-extension\LionBox-VSCode.vsix
set MODE=%~1
if "%MODE%"=="" set MODE=install

rem Result file: during INSTALL it goes next to the vsix (so the user, and the
rem verification script, can read what happened). During UNINSTALL it must NOT go
rem there: the uninstaller deletes that folder afterwards, and writing into it
rem would recreate the folder and leave an empty directory behind.
rem
rem NOTE: do NOT jump to :uninstall here. The code-detection block below has to run
rem first, otherwise CODE stays empty and the uninstall silently does nothing
rem (that is exactly the bug this comment is standing on: the extension stayed
rem installed and no result file was written).
if /i "%MODE%"=="uninstall" (
  set RESULT=%TEMP%\lionbox-vscode-uninstall.txt
) else (
  set RESULT=%HERE%vscode-extension\install-result.txt
  if not exist "%HERE%vscode-extension" mkdir "%HERE%vscode-extension" >nul 2>&1
)

rem ---- Locate the VS Code command line ----
rem Official VS Code does NOT put "code" on PATH by default, so probe the
rem usual install locations as well.
set CODE=
where code >nul 2>&1 && set CODE=code
if not defined CODE if exist "%LOCALAPPDATA%\Programs\Microsoft VS Code\bin\code.cmd" set CODE=%LOCALAPPDATA%\Programs\Microsoft VS Code\bin\code.cmd
if not defined CODE if exist "%ProgramFiles%\Microsoft VS Code\bin\code.cmd" set CODE=%ProgramFiles%\Microsoft VS Code\bin\code.cmd
if not defined CODE if exist "%ProgramFiles(x86)%\Microsoft VS Code\bin\code.cmd" set CODE=%ProgramFiles(x86)%\Microsoft VS Code\bin\code.cmd
if not defined CODE if exist "%LOCALAPPDATA%\Programs\Microsoft VS Code Insiders\bin\code-insiders.cmd" set CODE=%LOCALAPPDATA%\Programs\Microsoft VS Code Insiders\bin\code-insiders.cmd
if not defined CODE if exist "%LOCALAPPDATA%\Programs\cursor\resources\app\bin\cursor.cmd" set CODE=%LOCALAPPDATA%\Programs\cursor\resources\app\bin\cursor.cmd

rem Locate the VS Code command line before the uninstall branch uses it.
if /i "%MODE%"=="uninstall" goto :uninstall

if not defined CODE (
  echo [%DATE% %TIME%] VS Code command line not found; skipped auto-install.> "%RESULT%"
  echo The extension file is here - install it manually from the Extensions view>> "%RESULT%"
  echo ^(Extensions - ... - Install from VSIX^): %VSIX%>> "%RESULT%"
  exit /b 0
)

if not exist "%VSIX%" (
  echo [%DATE% %TIME%] Extension package missing: %VSIX%> "%RESULT%"
  exit /b 0
)

echo [%DATE% %TIME%] Installing VS Code extension ...> "%RESULT%"
call "%CODE%" --install-extension "%VSIX%" --force >> "%RESULT%" 2>&1
set RC=%ERRORLEVEL%
echo [%DATE% %TIME%] exit=%RC% via %CODE%>> "%RESULT%"
if "%RC%"=="0" (
  echo [%DATE% %TIME%] OK: lioncode.lionbox installed.>> "%RESULT%"
) else (
  echo [%DATE% %TIME%] FAILED with exit=%RC%. Install manually from VSIX: %VSIX%>> "%RESULT%"
)
exit /b 0

:uninstall
if defined CODE (
  call "%CODE%" --uninstall-extension lioncode.lionbox >> "%RESULT%" 2>&1
  echo [%DATE% %TIME%] lioncode.lionbox removed from VS Code.>> "%RESULT%"
)
exit /b 0
