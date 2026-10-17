@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-server.ps1" -Action status %*
set "launcherExit=%errorlevel%"
pause
exit /b %launcherExit%
