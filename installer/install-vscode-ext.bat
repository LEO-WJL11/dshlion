@echo off
rem Install/uninstall the LionBox extension + Simplified Chinese language pack into the
rem BUNDLED code-server (VS Code Web). ASCII only: cmd decodes .bat with the OEM code page.
setlocal
set HERE=%~dp0
set NODE=%HERE%node\node.exe
set CS=%HERE%code-server\node_modules\code-server\out\node\entry.js
set CSDATA=%HERE%code-server\data
set VSIX=%HERE%vscode-extension\LionBox-VSCode.vsix
set LANGPACK=%HERE%vscode-ext\zh-hans.vsix
set MODE=%~1
if "%MODE%"=="" set MODE=install

if /i "%MODE%"=="uninstall" (
  set RESULT=%TEMP%\lionbox-vscode-uninstall.txt
  if exist "%NODE%" if exist "%CS%" (
    "%NODE%" "%CS%" --uninstall-extension lioncode.lionbox --user-data-dir "%CSDATA%" >> "%RESULT%" 2>&1
    echo [%DATE% %TIME%] removed from bundled code-server.>> "%RESULT%"
  )
  exit /b 0
)

set RESULT=%HERE%vscode-extension\install-result.txt
if not exist "%HERE%vscode-extension" mkdir "%HERE%vscode-extension" >nul 2>&1
if not exist "%NODE%" ( echo [%DATE% %TIME%] bundled node missing: %NODE%> "%RESULT%" & exit /b 0 )
echo [%DATE% %TIME%] Installing extensions into bundled code-server ...> "%RESULT%"
if exist "%LANGPACK%" (
  "%NODE%" "%CS%" --install-extension "%LANGPACK%" --force --user-data-dir "%CSDATA%" >> "%RESULT%" 2>&1
  echo [%DATE% %TIME%] language pack exit=%ERRORLEVEL%>> "%RESULT%"
)
if exist "%VSIX%" (
  "%NODE%" "%CS%" --install-extension "%VSIX%" --force --user-data-dir "%CSDATA%" >> "%RESULT%" 2>&1
  echo [%DATE% %TIME%] agent extension exit=%ERRORLEVEL%>> "%RESULT%"
)
rem Preset the display language so the first launch is already Simplified Chinese.
if not exist "%CSDATA%\user-data" mkdir "%CSDATA%\user-data" >nul 2>&1
> "%CSDATA%\user-data\argv.json" echo {
>> "%CSDATA%\user-data\argv.json" echo   "locale": "zh-cn"
>> "%CSDATA%\user-data\argv.json" echo }
echo [%DATE% %TIME%] OK: done.>> "%RESULT%"
"%NODE%" "%CS%" --list-extensions --user-data-dir "%CSDATA%" >> "%RESULT%" 2>&1
exit /b 0
