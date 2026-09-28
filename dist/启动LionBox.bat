@echo off
chcp 65001 >nul 2>&1
rem ============================================================
rem  LionBox launcher - double click this file to start.
rem  The real logic lives in launcher.ps1.
rem
rem  NOTE: keep this file ASCII-ONLY. Never put Chinese text in a
rem  .bat file: cmd decodes each line with the system ANSI codepage,
rem  and once "chcp 65001" runs the byte offsets desync, so comment
rem  text ends up being executed as commands.
rem  All Chinese output comes from launcher.ps1 / stopper.ps1, which
rem  are saved as UTF-8 WITH BOM so PowerShell 5.1 reads them right.
rem ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher.ps1"
if errorlevel 1 pause
