@echo off
chcp 65001 >nul 2>&1
rem  LionBox stopper - double click to stop the model service and the Agent.
rem  Keep this file ASCII-ONLY (see the note in the launcher .bat).
rem  All Chinese output comes from stopper.ps1 (UTF-8 with BOM).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stopper.ps1"
rem  Pause about 3 seconds so the user can read the result above.
rem  Not using "timeout": it errors out when stdin is redirected.
ping -n 4 127.0.0.1 >nul 2>&1
exit /b 0
