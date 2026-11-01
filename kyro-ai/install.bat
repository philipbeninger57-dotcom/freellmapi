@echo off
REM KYRO AI installer. Options: -Models standard^|light^|none  -WithJava  -NoWinget
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\install.ps1" %*
pause
