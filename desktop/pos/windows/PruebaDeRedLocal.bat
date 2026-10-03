@echo off
rem Hub LAN: esta PC encuentra a las demas del local, y le llegan por TCP? Correla A LA VEZ en dos equipos de la misma red.
rem Anuncia un servicio de PRUEBA (no el real del POS) durante 30 segundos. Corre con javaw.exe, el MISMO programa que
rem la app: para el firewall de Windows, java.exe y javaw.exe son programas distintos.
echo Probando la red local durante 30 segundos... (el resultado se abre en el Bloc de notas)
set "SALIDA=%TEMP%\avoqado-prueba-red-local.txt"
start "" /wait "%~dp0jre\bin\javaw.exe" -cp "%~dp0lib\*" com.avoqado.escritorio.red.PruebaDeRedLocalKt 30 "%SALIDA%"
notepad "%SALIDA%"
