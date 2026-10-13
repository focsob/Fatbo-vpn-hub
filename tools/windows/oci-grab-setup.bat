@echo off
REM Fatbo VPNhub - Windows 一鍵搶 Oracle 免費 ARM 機
REM 雙擊呢個檔就得。佢會用 PowerShell 跑同一資料夾嘅 oci-grab-setup.ps1。
title Fatbo VPNhub - Grab Oracle free ARM
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0oci-grab-setup.ps1"
echo.
pause
