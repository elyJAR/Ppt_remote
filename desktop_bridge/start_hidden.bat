@echo off
cd /d "%~dp0"
powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File "start_background.ps1"