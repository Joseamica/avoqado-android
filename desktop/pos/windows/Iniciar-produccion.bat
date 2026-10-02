@echo off
rem Avoqado POS - la app de Android compilada para Windows (produccion).
rem Sin variables: el backend es el de produccion, fijo en el programa.
start "" "%~dp0jre\bin\javaw.exe" -cp "%~dp0lib\*" com.avoqado.pos.escritorio.MainKt
