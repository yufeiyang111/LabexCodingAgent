@echo off
title LabexAgent Backend - close this window to stop
cd /d "D:\LabexAgent"
echo Starting LabexAgent backend...
echo (env vars are loaded from .env by scripts\start_backend.ps1)
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\LabexAgent\scripts\start_backend.ps1"
echo.
echo Backend stopped. Press any key to close.
pause >nul
