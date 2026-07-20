@echo off
chcp 65001 >nul
title DocSScaner - RuStore build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-rustore-release.ps1"
echo.
pause
