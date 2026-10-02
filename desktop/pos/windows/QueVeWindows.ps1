# Qué ve Windows: impresoras, puertos COM, pantallas y aparatos. Sólo LEE; no cambia nada.
param([switch]$SinAbrir)
# Al empaquetar, la línea de abajo se estampa con la carpeta de datos de ESTE build (producción: %USERPROFILE%\.avoqado-pos;
# prueba: %APPDATA%\Avoqado POS). Sólo mira esa carpeta; si no existe NO la crea: deja el reporte en %TEMP% y lo dice.
$carpetaDeDatos = @CARPETA_DE_DATOS_PS@
$carpeta = Join-Path $carpetaDeDatos 'logs'
if (-not (Test-Path $carpeta)) {
  Write-Host "Todavía no existe $carpeta (Avoqado POS no se ha abierto con este build). El reporte se deja en %TEMP%."
  $carpeta = $env:TEMP
}
$salida = Join-Path $carpeta 'que-ve-windows.txt'
function Seccion($titulo, $bloque) {
  "`r`n===== $titulo ====="
  try { & $bloque | Out-String -Width 220 } catch { "no se pudo leer: $($_.Exception.Message)" }
}
& {
  "Qué ve Windows — $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
  Seccion 'Equipo' { Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer, Model, SystemType; Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, OSArchitecture }
  Seccion 'Impresoras (colas de Windows)' { Get-Printer | Select-Object Name, DriverName, PortName, PrinterStatus, Shared | Format-Table -AutoSize }
  Seccion 'Puertos de impresora' { Get-PrinterPort | Select-Object Name, Description, PrinterHostAddress, PortNumber | Format-Table -AutoSize }
  Seccion 'Puertos COM (registro SERIALCOMM)' { Get-ItemProperty -Path 'HKLM:\HARDWARE\DEVICEMAP\SERIALCOMM' -ErrorAction Stop }
  Seccion 'Aparatos: puertos, impresoras, USB-impresora, Bluetooth, monitores' {
    Get-PnpDevice -PresentOnly | Where-Object { $_.Class -in 'Ports','Printer','PrintQueue','USB','Bluetooth','Monitor','Image','HIDClass' } |
      Select-Object Class, FriendlyName, Status, InstanceId | Sort-Object Class, FriendlyName | Format-Table -AutoSize
  }
  Seccion 'Pantallas' {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.Screen]::AllScreens | Select-Object DeviceName, Primary, Bounds, WorkingArea | Format-Table -AutoSize
  }
  Seccion 'Pantalla táctil (puntos)' {
    Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern int GetSystemMetrics(int n);' -Name M -Namespace W
    "Puntos táctiles: " + [W.M]::GetSystemMetrics(95)
  }
} | Out-File -FilePath $salida -Encoding utf8
if (-not $SinAbrir) { notepad $salida } else { Get-Content $salida }
