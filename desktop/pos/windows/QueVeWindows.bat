@echo off
rem Que ve Windows: impresoras, puertos COM, pantallas. Solo lee; no cambia nada.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0QueVeWindows.ps1"
