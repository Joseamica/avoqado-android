@echo off
rem Abre el diagnostico de ESTE build. Al empaquetar, la linea de abajo se estampa con la carpeta de su modo:
rem produccion = %USERPROFILE%\.avoqado-pos, prueba = %APPDATA%\Avoqado POS. Nunca mira la del otro modo.
set "DIAG=@CARPETA_DE_DATOS@\logs\diagnostico.txt"
if not exist "%DIAG%" (
  echo Todavia no hay diagnostico: no existe %DIAG%
  echo Abre Avoqado POS una vez con Iniciar.bat y vuelve a intentarlo.
  pause
  exit /b 1
)
notepad "%DIAG%"
