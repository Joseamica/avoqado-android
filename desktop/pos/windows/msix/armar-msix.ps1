# Arma el MSIX del POS de Windows desde la carpeta «Avoqado POS» del ZIP (lib\, jre\, msix\):
# lanzador nativo con jpackage (Avoqado POS.exe + runtime\ + app\), manifiesto con el editor sellado, logos y makeappx.
# Con -Pfx además lo firma con signtool (sin -Pfx sale SIN firmar: así se sube a la Microsoft Store, que lo firma).
# Uso: powershell -NoProfile -ExecutionPolicy Bypass -File armar-msix.ps1 -Carpeta "C:\…\Avoqado POS"
#        -Herramientas C:\…\herramientas -Editor "CN=…" -Salida C:\…\salida [-Pfx prueba.pfx -ClavePfx <clave>]
#        [-Version X.Y.Z.0] [-JavaOpciones "-Dclave=valor -Dotra=valor"]   (sólo en el paquete de prueba)
# Las opciones van separadas por espacios, sin comillas dentro (medido con powershell -File: llegan tal cual al .cfg).
param(
  [Parameter(Mandatory = $true)][string]$Carpeta,
  [Parameter(Mandatory = $true)][string]$Herramientas,
  [Parameter(Mandatory = $true)][string]$Editor,
  [Parameter(Mandatory = $true)][string]$Salida,
  [string]$Pfx,
  [string]$ClavePfx,
  [string]$Version,
  [string]$JavaOpciones
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Exigir([string]$Ruta, [string]$Que) {
  if (-not (Test-Path -LiteralPath $Ruta -PathType Leaf)) { throw "Falta $Que ($Ruta)" }
}

# Un PNG cuadrado de $Lado px, escalado con HighQualityBicubic sobre fondo transparente (bytes del PNG).
function PngEscalado([string]$Origen, [int]$Lado) {
  $imagen = [System.Drawing.Image]::FromFile($Origen)
  $lienzo = New-Object System.Drawing.Bitmap($Lado, $Lado, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $grafico = [System.Drawing.Graphics]::FromImage($lienzo)
  $atributos = New-Object System.Drawing.Imaging.ImageAttributes
  $memoria = New-Object System.IO.MemoryStream
  try {
    $grafico.Clear([System.Drawing.Color]::Transparent)
    $grafico.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $grafico.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $grafico.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $grafico.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $atributos.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)   # sin halo en los bordes
    $escala = [Math]::Min($Lado / $imagen.Width, $Lado / $imagen.Height)   # conserva la proporción, centrado
    $ancho = [int][Math]::Round($imagen.Width * $escala)
    $alto = [int][Math]::Round($imagen.Height * $escala)
    $destino = New-Object System.Drawing.Rectangle([int](($Lado - $ancho) / 2), [int](($Lado - $alto) / 2), $ancho, $alto)
    $grafico.DrawImage($imagen, $destino, 0, 0, $imagen.Width, $imagen.Height, [System.Drawing.GraphicsUnit]::Pixel, $atributos)
    $lienzo.Save($memoria, [System.Drawing.Imaging.ImageFormat]::Png)
    return , $memoria.ToArray()   # la coma evita que PowerShell desarme el arreglo de bytes
  } finally {
    $memoria.Dispose(); $atributos.Dispose(); $grafico.Dispose(); $lienzo.Dispose(); $imagen.Dispose()
  }
}

# Un .ico con una entrada PNG por tamaño: ICONDIR (6 bytes) + un ICONDIRENTRY (16 bytes) por tamaño + los PNG.
function EscribirIco([string]$Origen, [int[]]$Lados, [string]$Destino) {
  $pngs = New-Object 'System.Collections.Generic.List[byte[]]'
  foreach ($lado in $Lados) { $pngs.Add((PngEscalado $Origen $lado)) }
  $memoria = New-Object System.IO.MemoryStream
  $escritor = New-Object System.IO.BinaryWriter($memoria)
  try {
    $escritor.Write([UInt16]0); $escritor.Write([UInt16]1); $escritor.Write([UInt16]$Lados.Count)
    $desplazamiento = 6 + 16 * $Lados.Count
    for ($i = 0; $i -lt $Lados.Count; $i++) {
      $medida = if ($Lados[$i] -ge 256) { 0 } else { $Lados[$i] }   # 0 = 256 px
      $escritor.Write([byte]$medida); $escritor.Write([byte]$medida)
      $escritor.Write([byte]0); $escritor.Write([byte]0)             # sin paleta; reservado
      $escritor.Write([UInt16]1); $escritor.Write([UInt16]32)        # planos; bits por píxel
      $escritor.Write([UInt32]$pngs[$i].Length); $escritor.Write([UInt32]$desplazamiento)
      $desplazamiento += $pngs[$i].Length
    }
    foreach ($png in $pngs) { $escritor.Write($png) }
    $escritor.Flush()
    [IO.File]::WriteAllBytes($Destino, $memoria.ToArray())
  } finally { $escritor.Dispose(); $memoria.Dispose() }
}

# --- 1. Entradas ---
if (-not (Test-Path -LiteralPath $Carpeta -PathType Container)) { throw "No existe la carpeta $Carpeta (la «Avoqado POS» del ZIP descomprimido)" }
if (-not (Test-Path -LiteralPath $Herramientas -PathType Container)) { throw "No existe la carpeta de herramientas $Herramientas (la deja herramientas.ps1)" }
$Carpeta = (Resolve-Path -LiteralPath $Carpeta).Path
$Herramientas = (Resolve-Path -LiteralPath $Herramientas).Path
Exigir (Join-Path $Carpeta 'lib\pos.jar') 'el jar principal'
Exigir (Join-Path $Carpeta 'jre\bin\javaw.exe') 'el JRE de Windows'
Exigir (Join-Path $Carpeta 'jre\bin\sqliteJni.dll') 'la DLL de SQLite en jre\bin (sin ella, SQLite se copia a %TEMP% en cada arranque)'
$manifiestoZip = Join-Path $Carpeta 'msix\AppxManifest.xml'
$logo = Join-Path $Carpeta 'msix\logo.png'
$versionTxt = Join-Path $Carpeta 'msix\version.txt'
Exigir $manifiestoZip 'el manifiesto del ZIP'
Exigir $logo 'el logo'
Exigir $versionTxt 'version.txt'
$jpackage = Join-Path $Herramientas 'jdk\bin\jpackage.exe'
$makeappx = Join-Path $Herramientas 'sdk\makeappx.exe'
$signtool = Join-Path $Herramientas 'sdk\signtool.exe'
Exigir $jpackage 'jpackage (corre herramientas.ps1)'
Exigir $makeappx 'makeappx (corre herramientas.ps1)'
Exigir $signtool 'signtool (corre herramientas.ps1)'

$manifiesto = New-Object System.Xml.XmlDocument
$manifiesto.PreserveWhitespace = $true
$manifiesto.Load($manifiestoZip)
$identidadXml = $manifiesto.Package.Identity   # el elemento <Identity> (se lee y se sella por atributo)
$nombre = $identidadXml.GetAttribute('Name')
# Producción es todo lo que NO es el paquete de prueba: ahí no se cuela ni una opción de Java (ni un túnel).
if ($nombre -ne 'Avoqado.POS.Prueba' -and $PSBoundParameters.ContainsKey('JavaOpciones')) {
  throw "El paquete de producción no lleva opciones de Java (el manifiesto dice Name=$nombre)"
}
$opciones = @()
if ($JavaOpciones) { $opciones = @($JavaOpciones -split '\s+' | Where-Object { $_ }) }

# --- 2. Versión: -Version gana sobre version.txt ---
$ver = if ($Version) { $Version } else { ([IO.File]::ReadAllText($versionTxt)).Trim() }
if ($ver -notmatch '^\d{1,5}\.\d{1,5}\.\d{1,5}\.0$' -or @($ver.Split('.') | Where-Object { [int]$_ -gt 65535 }).Count -gt 0) {
  throw "La versión «$ver» no sirve para MSIX: tiene que ser X.Y.Z.0 con cada parte entre 0 y 65535"
}
$verApp = $ver.Substring(0, $ver.LastIndexOf('.'))   # X.Y.Z para el lanzador

# --- 3. El certificado tiene que ser del MISMO editor que el manifiesto ---
if ($Pfx) {
  Exigir $Pfx 'el certificado .pfx'
  try {
    $certificado = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList (Resolve-Path -LiteralPath $Pfx).Path, ([string]$ClavePfx)
  } catch {
    throw "No pude abrir el certificado $Pfx (¿la clave es la de prueba-clave.txt?): $($_.Exception.Message)"
  }
  $sujeto = $certificado.Subject
  $certificado.Reset()
  if ($sujeto -ne $Editor) { throw "El certificado es de $sujeto y el paquete dice ${Editor}: Windows no lo instalaría" }
}

New-Item -ItemType Directory -Force -Path $Salida | Out-Null
$Salida = (Resolve-Path -LiteralPath $Salida).Path
$temporal = Join-Path $Salida ('.armando-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $temporal | Out-Null
try {
  # --- 4. Ícono del lanzador ---
  $ico = Join-Path $temporal 'Avoqado POS.ico'
  EscribirIco $logo @(16, 32, 48, 256) $ico

  # --- 5. jpackage: lanzador nativo + runtime\ (el JRE del ZIP) + app\ (los jars) ---
  $dirImagen = Join-Path $temporal 'imagen'
  $argumentos = @('--type', 'app-image', '--name', 'Avoqado POS', '--dest', $dirImagen,
    '--input', (Join-Path $Carpeta 'lib'), '--main-jar', 'pos.jar', '--main-class', 'com.avoqado.pos.escritorio.MainKt',
    '--runtime-image', (Join-Path $Carpeta 'jre'), '--icon', $ico, '--app-version', $verApp, '--vendor', 'Avoqado',
    '--temp', (Join-Path $temporal 'jpackage'))   # sus temporales también aquí: se borran al final
  foreach ($opcion in $opciones) { $argumentos += @('--java-options', $opcion) }
  Write-Host "jpackage $verApp ($(@($opciones).Count) opciones de Java)"
  & $jpackage @argumentos
  if ($LASTEXITCODE -ne 0) { throw "jpackage falló (código $LASTEXITCODE)" }
  $imagen = Join-Path $dirImagen 'Avoqado POS'
  Exigir (Join-Path $imagen 'Avoqado POS.exe') 'el lanzador que debía dejar jpackage'

  # El lanzador tiene que llevar TODOS los jars de lib\ en app.classpath; si jpackage sólo puso el principal, se reescribe.
  $cfg = Join-Path $imagen 'app\Avoqado POS.cfg'
  Exigir $cfg 'el .cfg del lanzador'
  $jars = @(@('pos.jar') + @(Get-ChildItem -LiteralPath (Join-Path $Carpeta 'lib') -Filter '*.jar' -File |
        ForEach-Object { $_.Name } | Where-Object { $_ -ne 'pos.jar' } | Sort-Object))
  $lineas = @([IO.File]::ReadAllLines($cfg))
  $enCfg = @($lineas | Where-Object { $_ -like 'app.classpath=*' } |
      ForEach-Object { $_.Substring('app.classpath='.Length) -split ';' } | Where-Object { $_ } | ForEach-Object { [IO.Path]::GetFileName($_) })
  $faltan = @($jars | Where-Object { $enCfg -notcontains $_ })
  if ($faltan.Count -gt 0) {
    Write-Host "jpackage dejó fuera $($faltan.Count) de $($jars.Count) jars en app.classpath: lo reescribo con todos"
    $nueva = 'app.classpath=' + (($jars | ForEach-Object { '$APPDIR\' + $_ }) -join ';')
    $reescritas = New-Object System.Collections.Generic.List[string]
    $puesta = $false
    foreach ($linea in $lineas) {
      if ($linea -like 'app.classpath=*') { if (-not $puesta) { $reescritas.Add($nueva); $puesta = $true } }
      else { $reescritas.Add($linea) }
    }
    [IO.File]::WriteAllLines($cfg, $reescritas)
    $enCfg = @($jars)
  }
  $sinArchivo = @($jars | Where-Object { -not (Test-Path -LiteralPath (Join-Path $imagen "app\$_")) })
  if ($sinArchivo.Count -gt 0) { throw "app\ no trae estos jars del classpath: $($sinArchivo -join ', ')" }
  Write-Host "app.classpath: $($enCfg.Count) jars (lib\ trae $($jars.Count))"
  Exigir (Join-Path $imagen 'runtime\bin\sqliteJni.dll') 'runtime\bin\sqliteJni.dll en la imagen (sin ella, SQLite se copia a %TEMP%)'

  # --- 6. Manifiesto con el editor y la versión sellados, y los logos del paquete ---
  $identidadXml.SetAttribute('Publisher', $Editor)
  $identidadXml.SetAttribute('Version', $ver)
  $ajustes = New-Object System.Xml.XmlWriterSettings
  $ajustes.Encoding = New-Object System.Text.UTF8Encoding($false)
  $escritorXml = [System.Xml.XmlWriter]::Create((Join-Path $imagen 'AppxManifest.xml'), $ajustes)
  try { $manifiesto.Save($escritorXml) } finally { $escritorXml.Dispose() }
  $assets = Join-Path $imagen 'Assets'
  New-Item -ItemType Directory -Force -Path $assets | Out-Null
  foreach ($logoPaquete in @(@('StoreLogo.png', 50), @('Square44x44Logo.png', 44), @('Square150x150Logo.png', 150))) {
    [IO.File]::WriteAllBytes((Join-Path $assets $logoPaquete[0]), (PngEscalado $logo $logoPaquete[1]))
  }

  # --- 7. makeappx ---
  $msix = Join-Path $Salida ('{0}_{1}_x64.msix' -f $nombre, $ver)
  & $makeappx pack /o /d $imagen /p $msix |   # sin el renglón por archivo (~400) ni los renglones vacíos
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notlike 'Processing *' }
  if ($LASTEXITCODE -ne 0) { throw "makeappx falló (código $LASTEXITCODE)" }

  # --- 8. Firma (sólo con -Pfx) ---
  if ($Pfx) {
    $argumentosFirma = @('sign', '/fd', 'SHA256', '/a', '/f', (Resolve-Path -LiteralPath $Pfx).Path)
    if ($ClavePfx) { $argumentosFirma += @('/p', $ClavePfx) }   # PowerShell 5.1 se come un argumento vacío: sin clave, sin /p
    & $signtool @argumentosFirma $msix
    if ($LASTEXITCODE -ne 0) { throw "signtool falló (código $LASTEXITCODE)" }
  } else {
    Write-Host 'Sin -Pfx: el paquete queda SIN firmar (para la Microsoft Store, que lo firma).'
  }

  # --- 9. Resultado ---
  Write-Host "MSIX=$msix"
  Write-Host "SHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $msix).Hash)"
} finally {
  if (Test-Path -LiteralPath $temporal) { Remove-Item -LiteralPath $temporal -Recurse -Force }
}
