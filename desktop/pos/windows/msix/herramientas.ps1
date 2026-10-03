# Herramientas para armar el MSIX del POS de Windows: JDK 17 (jpackage) y makeappx/signtool del SDK de Windows.
# Se bajan a -Destino SIN instalar nada en el sistema. Cada descarga se compara contra una huella SHA-256 FIJA (abajo)
# ANTES de abrirla: si no cuadra, se borra y truena con las dos huellas.
# Cada herramienta queda con una marca, <carpeta>\.avoqado-herramienta, con su versión. Si -Destino ya trae la carpeta con
# la marca de esta versión, no vuelve a bajar; con la marca de OTRA versión, la reemplaza (es de este script); SIN marca,
# truena y no la toca: este script nunca borra lo que no creó.
# Uso: powershell -NoProfile -ExecutionPolicy Bypass -File herramientas.ps1 -Destino C:\ruta\herramientas
param([Parameter(Mandatory = $true)][string]$Destino)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # la barra de progreso de PowerShell 5.1 hace lentísimas las descargas
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

# Versiones y huellas FIJAS: cambiarlas es una decisión, no un accidente. Para subir una herramienta se cambian sus tres datos.
# JDK 17 de Temurin (ZIP, no instalador). Enlace y huella de la API de Adoptium:
#   https://api.adoptium.net/v3/assets/release_name/eclipse/<versión>?architecture=x64&os=windows&image_type=jdk
#   ⇒ binaries[].package.link y binaries[].package.checksum
$VersionJdk = 'jdk-17.0.20.1+1'
$UrlJdk = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$HuellaJdk = 'E53A79C3C3D86865BD7E787903884331068E71321714FFD44F145785AFFC7CB0'
# makeappx y signtool: NuGet Microsoft.Windows.SDK.BuildTools. Huella = SHA-256 del .nupkg
# (cuadra con el SHA-512 que publica el catálogo de nuget.org para esta versión).
$VersionSdk = '10.0.28000.2705'
$UrlSdk = "https://www.nuget.org/api/v2/package/Microsoft.Windows.SDK.BuildTools/$VersionSdk"
$HuellaSdk = '8BFDFB6CA2633F531CF80B5FA22512BA61A394D7988F0970DB83BAADC67929ED'

$NombreMarca = '.avoqado-herramienta'

$destinoCreado = -not (Test-Path -LiteralPath $Destino)   # si lo crea ESTA corrida y al final queda vacío, se quita
New-Item -ItemType Directory -Force -Path $Destino | Out-Null
$Destino = (Resolve-Path -LiteralPath $Destino).Path
$carpetaJdk = Join-Path $Destino 'jdk'
$carpetaSdk = Join-Path $Destino 'sdk'
$jpackage = Join-Path $carpetaJdk 'bin\jpackage.exe'
$makeappx = Join-Path $carpetaSdk 'makeappx.exe'
$signtool = Join-Path $carpetaSdk 'signtool.exe'
$temporal = Join-Path $Destino ('descargas-' + [guid]::NewGuid().ToString('N').Substring(0, 8))   # única por corrida

# Truena si $Carpeta (que existe) no trae la marca de este script; si la trae, devuelve la versión que dice.
function VersionDeLaMarca([string]$Carpeta) {
  $marca = Join-Path $Carpeta $NombreMarca
  if (-not (Test-Path -LiteralPath $Carpeta -PathType Container) -or -not (Test-Path -LiteralPath $marca -PathType Leaf)) {
    throw "$Carpeta ya existe y no la creó este script: elige otro -Destino"
  }
  return [IO.File]::ReadAllText($marca).Trim()
}

# 'falta' (no existe) · 'lista' (marca de esta versión y trae sus archivos) · 'nuestra' (marca de otra versión, o le faltan
# archivos: se puede reemplazar). Si existe SIN marca, truena sin tocarla.
function EstadoDe([string]$Carpeta, [string]$Version, [string[]]$Archivos) {
  if (-not (Test-Path -LiteralPath $Carpeta)) { return 'falta' }
  $versionMarca = VersionDeLaMarca $Carpeta
  if ($versionMarca -ne $Version) {
    Write-Host "$Carpeta es de este script pero de la versión $versionMarca`: la reemplazo por $Version"
    return 'nuestra'
  }
  foreach ($archivo in $Archivos) {
    if (-not (Test-Path -LiteralPath (Join-Path $Carpeta $archivo) -PathType Leaf)) {
      Write-Host "$Carpeta es de este script pero le falta $archivo`: la vuelvo a bajar"
      return 'nuestra'
    }
  }
  return 'lista'
}

# Baja $Url a $Archivo y compara su SHA-256 con $Esperada ANTES de que nadie lo abra. Si no cuadra, lo borra y truena.
function BajarVerificado([string]$Url, [string]$Archivo, [string]$Esperada) {
  Write-Host "Bajando $Url"
  Invoke-WebRequest -Uri $Url -OutFile $Archivo -UseBasicParsing
  $huella = (Get-FileHash -Algorithm SHA256 -LiteralPath $Archivo).Hash
  $bytes = (Get-Item -LiteralPath $Archivo).Length
  if ($huella -ne $Esperada) {
    try { Remove-Item -LiteralPath $Archivo -Force } catch { }   # si no se deja, se va con la carpeta temporal
    throw ('La descarga de {0} NO es la esperada: su SHA-256 es {1} ({2:N0} bytes) y debía ser {3}. La borré sin abrirla.' -f
      $Url, $huella, $bytes, $Esperada.ToUpperInvariant())
  }
  Write-Host ('SHA-256 de {0}: {1} ({2:N0} bytes), igual al esperado' -f (Split-Path $Archivo -Leaf), $huella, $bytes)
}

# Pone $Nueva (ya completa, dentro del temporal) en $Carpeta y le escribe la marca. Una versión vieja NUESTRA se aparta
# primero al temporal (se borra con él): si apartarla falla, truena sin haber borrado nada.
function Colocar([string]$Nueva, [string]$Carpeta, [string]$Version) {
  [IO.File]::WriteAllText((Join-Path $Nueva $NombreMarca), $Version)   # la marca va DENTRO antes de moverla: nunca queda una a medias sin marca
  $apartada = $null
  if (Test-Path -LiteralPath $Carpeta) {
    $null = VersionDeLaMarca $Carpeta   # vuelve a comprobar que sigue siendo nuestra justo antes de apartarla
    $apartada = Join-Path $temporal ('vieja-' + (Split-Path $Carpeta -Leaf))
    Move-Item -LiteralPath $Carpeta -Destination $apartada
  }
  try {
    Move-Item -LiteralPath $Nueva -Destination $Carpeta
  } catch {
    if ($apartada) { Move-Item -LiteralPath $apartada -Destination $Carpeta }   # deja la vieja como estaba
    throw
  }
}

function BorrarTemporal([string]$Ruta) {
  for ($intento = 1; $intento -le 3; $intento++) {
    try { Remove-Item -LiteralPath $Ruta -Recurse -Force; return } catch {
      if ($intento -eq 3) { Write-Warning "No pude borrar la carpeta temporal $Ruta`: $($_.Exception.Message). Bórrala a mano."; return }
      Start-Sleep -Seconds 2   # un antivirus que todavía revisa lo recién bajado suele soltarlo en segundos
    }
  }
}

$temporalCreado = $false
try {
  # Las DOS carpetas se revisan antes de bajar nada: si alguna es ajena, truena sin haber tocado la red ni el disco.
  $estadoJdk = EstadoDe $carpetaJdk $VersionJdk @('bin\jpackage.exe')
  $estadoSdk = EstadoDe $carpetaSdk $VersionSdk @('makeappx.exe', 'signtool.exe')
  if ($estadoJdk -ne 'lista' -or $estadoSdk -ne 'lista') {
    New-Item -ItemType Directory -Path $temporal | Out-Null   # sin -Force: si ya existiera, truena en vez de adoptarla
    $temporalCreado = $true
  }

  # --- JDK 17 de Temurin: sólo se usa su jpackage.exe ---
  if ($estadoJdk -eq 'lista') {
    Write-Host "Ya está el JDK $VersionJdk`: $jpackage"
  } else {
    $zipJdk = Join-Path $temporal 'jdk.zip'
    BajarVerificado $UrlJdk $zipJdk $HuellaJdk
    $extraidoJdk = Join-Path $temporal 'jdk'
    Expand-Archive -LiteralPath $zipJdk -DestinationPath $extraidoJdk -Force
    $binario = @(Get-ChildItem -LiteralPath $extraidoJdk -Recurse -Filter 'jpackage.exe' | Where-Object { $_.Directory.Name -eq 'bin' })
    if ($binario.Count -ne 1) { throw "El ZIP del JDK no trae un único bin\jpackage.exe (encontré $($binario.Count))" }
    $raizJdk = $binario[0].Directory.Parent.FullName
    $release = Join-Path $raizJdk 'release'
    if (Test-Path -LiteralPath $release) {
      Write-Host ('JDK: ' + ((Get-Content -LiteralPath $release | Where-Object { $_ -like 'JAVA_RUNTIME_VERSION=*' }) -join ' '))
    }
    Colocar $raizJdk $carpetaJdk $VersionJdk
  }

  # --- makeappx y signtool: NuGet Microsoft.Windows.SDK.BuildTools (un .nupkg es un ZIP) ---
  if ($estadoSdk -eq 'lista') {
    Write-Host "Ya están makeappx y signtool $VersionSdk`: $carpetaSdk"
  } else {
    $zipSdk = Join-Path $temporal "buildtools-$VersionSdk.zip"   # Expand-Archive de PowerShell 5.1 sólo acepta .zip
    BajarVerificado $UrlSdk $zipSdk $HuellaSdk
    $extraidoSdk = Join-Path $temporal 'sdk-extraido'
    Expand-Archive -LiteralPath $zipSdk -DestinationPath $extraidoSdk -Force
    $x64 = @(Get-ChildItem -LiteralPath (Join-Path $extraidoSdk 'bin') -Directory |
        ForEach-Object { Join-Path $_.FullName 'x64' } | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'makeappx.exe') })
    if ($x64.Count -ne 1) { throw "El paquete Microsoft.Windows.SDK.BuildTools $VersionSdk no trae un único bin\<versión>\x64\makeappx.exe" }
    $armadoSdk = Join-Path $temporal 'sdk'
    New-Item -ItemType Directory -Path $armadoSdk | Out-Null
    # Además de los dos .exe y las DLL: sus .manifest (ensamblados lado a lado: sin ellos los .exe no arrancan),
    # el .ini de wintrust y los mensajes en inglés de AppxPackaging (en-US\*.mui).
    Get-ChildItem -LiteralPath $x64[0] -File |
      Where-Object { ($_.Name -in @('makeappx.exe', 'signtool.exe')) -or ($_.Extension -in @('.dll', '.manifest', '.ini')) } |
      Copy-Item -Destination $armadoSdk
    $mensajes = Join-Path $x64[0] 'en-US'
    if (Test-Path -LiteralPath $mensajes) { Copy-Item -LiteralPath $mensajes -Destination $armadoSdk -Recurse }
    Colocar $armadoSdk $carpetaSdk $VersionSdk
  }
} finally {
  # Lo único que se borra: la carpeta temporal de ESTA corrida y, si esta corrida creó -Destino y quedó vacío, -Destino.
  if ($temporalCreado) { BorrarTemporal $temporal }
  if ($destinoCreado) { try { [IO.Directory]::Delete($Destino, $false) } catch { } }   # no recursivo: con algo dentro, no se borra
}

foreach ($herramienta in $jpackage, $makeappx, $signtool) {
  if (-not (Test-Path -LiteralPath $herramienta)) { throw "Falta $herramienta después de bajar las herramientas" }
}
Write-Host "JPACKAGE=$jpackage"
Write-Host "MAKEAPPX=$makeappx"
Write-Host "SIGNTOOL=$signtool"
