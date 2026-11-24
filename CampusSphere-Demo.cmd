@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-CampusSphereDemo.ps1"
set "demo_exit=%errorlevel%"
pause
exit /b %demo_exit%
