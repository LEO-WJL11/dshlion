@echo off
chcp 936 >nul
rem 把 LionBox-Desktop-1.5.1-Setup.exe 的分卷拼回完整安装包
setlocal
cd /d "%%~dp0"
set OUT=LionBox-Desktop-1.5.1-Setup.exe
if exist "%%OUT%%" del "%%OUT%%"
copy /b "LionBox-Desktop-1.5.1-Setup.exe.part1"+"LionBox-Desktop-1.5.1-Setup.exe.part2" "%OUT%" >nul
echo.
echo 已拼出 %%OUT%%
echo 校验：certutil -hashfile "%%OUT%%" SHA256
echo 期望：AB0E5A3F47377B34BEF960371F92958815CAE449C4205B5058829528F9E84A65
pause
