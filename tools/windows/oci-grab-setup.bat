@echo off
REM Fatbo VPNhub - Windows one-click: grab a free Oracle ARM instance.
REM Double-click this file. It runs oci-grab-setup.ps1 in the same folder.
title Fatbo VPNhub - Grab Oracle free ARM
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0oci-grab-setup.ps1"
echo.
pause
