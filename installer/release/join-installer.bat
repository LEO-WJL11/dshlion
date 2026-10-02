@echo off
rem Join the LionBox installer volumes back into one exe, then check the hash.
rem Put every part file and this .bat in the same folder, then double-click.
setlocal
cd /d "%~dp0"
set OUT=LionBox-Setup-1.5.23.exe
if exist "%OUT%" del "%OUT%"
copy /b "LionBox-Setup-1.5.23.exe.part001"+"LionBox-Setup-1.5.23.exe.part002"+"LionBox-Setup-1.5.23.exe.part003" "%OUT%" >nul
echo Done: %OUT%
echo.
echo Verify with:  certutil -hashfile "%OUT%" SHA256
echo Expected  :  CE65C069C93884DE76CEC6845649365F17A8696CEC0C8E6B07B6976CCECCD519
echo.
echo Run %OUT% AS ADMINISTRATOR (right click, Run as administrator).
pause
