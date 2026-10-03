# Certificado de PRUEBA para firmar el MSIX en una PC de pruebas. Nunca para clientes: el de la Store lo firma Microsoft.
# Crea «CN=Avoqado POS Prueba» en CurrentUser\My si no existe; deja en -Destino prueba.cer, prueba.pfx y prueba-clave.txt
# (clave aleatoria del pfx), e importa prueba.cer a LocalMachine\TrustedPeople para que Windows confíe en el paquete.
# El pfx y su clave son la LLAVE de firma: -Destino y esos dos archivos quedan sin herencia y sólo para quien corre esto,
# SYSTEM y Administradores. Si no se puede, truena sin exportar nada. Por eso -Destino tiene que ser una carpeta nueva,
# vacía, o una que sólo traiga estos tres archivos (a una carpeta con más cosas le quitaría el acceso a los demás).
# Nada de esto va al repo. Pide administrador (por LocalMachine). La clave nunca se imprime: se lee de prueba-clave.txt.
# -Quitar: lo deshace todo (CurrentUser\My, LocalMachine\TrustedPeople y los archivos de -Destino).
# Uso: powershell -NoProfile -ExecutionPolicy Bypass -File certificado-de-prueba.ps1 -Destino C:\ruta\certificado [-Quitar]
param(
  [Parameter(Mandatory = $true)][string]$Destino,
  [switch]$Quitar
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Sujeto = 'CN=Avoqado POS Prueba'
$archivos = @('prueba.pfx', 'prueba-clave.txt', 'prueba.cer')

$identidad = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identidad.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Esto toca LocalMachine\TrustedPeople: ábrelo en una PowerShell «Ejecutar como administrador».'
}

if ($Quitar) {
  foreach ($almacen in @('Cert:\CurrentUser\My', 'Cert:\LocalMachine\TrustedPeople')) {
    foreach ($certificado in @(Get-ChildItem -LiteralPath $almacen | Where-Object { $_.Subject -eq $Sujeto })) {
      $ruta = Join-Path $almacen $certificado.Thumbprint
      if ($almacen -eq 'Cert:\CurrentUser\My') { Remove-Item -LiteralPath $ruta -DeleteKey } else { Remove-Item -LiteralPath $ruta }
      Write-Host ('Quitado de {0}: {1}' -f $almacen, $certificado.Thumbprint)
    }
  }
  foreach ($archivo in $archivos) {
    $ruta = Join-Path $Destino $archivo
    if (Test-Path -LiteralPath $ruta) { Remove-Item -LiteralPath $ruta -Force; Write-Host "Borrado $ruta" }
  }
  Write-Host 'Certificado de prueba quitado.'
  return
}

# Las únicas identidades con acceso a la llave, por SID (en un Windows en español los nombres cambian):
# quien corre esto, SYSTEM (S-1-5-18) y el grupo Administradores (S-1-5-32-544).
$sidsPermitidos = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')

function NombreDe([string]$Sid) {
  try { return ([Security.Principal.SecurityIdentifier]::new($Sid)).Translate([Security.Principal.NTAccount]).Value } catch { return $Sid }
}

# Deja $Ruta SIN herencia y con control total sólo para $sidsPermitidos. En una carpeta, lo que se cree dentro hereda eso.
function Restringir([string]$Ruta, [switch]$Carpeta) {
  if ($Carpeta) {
    $seguridad = [Security.AccessControl.DirectorySecurity]::new()
    $herencia = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
  } else {
    $seguridad = [Security.AccessControl.FileSecurity]::new()
    $herencia = [Security.AccessControl.InheritanceFlags]::None
  }
  $seguridad.SetAccessRuleProtection($true, $false)   # no hereda de la carpeta de arriba ni se queda con lo heredado
  foreach ($sid in $sidsPermitidos) {
    $seguridad.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new($sid), [Security.AccessControl.FileSystemRights]::FullControl,
        $herencia, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
  }
  (Get-Item -LiteralPath $Ruta -Force).SetAccessControl($seguridad)   # sólo escribe los permisos; el dueño no se toca
}

# Quién, aparte de las tres permitidas, puede entrar a $Ruta según su ACL YA escrita (nada = nadie).
function Ajenos([string]$Ruta) {
  $acl = Get-Acl -LiteralPath $Ruta
  if (-not $acl.AreAccessRulesProtected) { 'hereda los permisos de la carpeta de arriba' }
  $dueno = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
  if ($sidsPermitidos -notcontains $dueno) { 'su dueño, ' + (NombreDe $dueno) }
  foreach ($regla in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
    if ($regla.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        $sidsPermitidos -notcontains $regla.IdentityReference.Value) {
      NombreDe $regla.IdentityReference.Value
    }
  }
}

# 1) La carpeta, ANTES de que exista ningún secreto: nueva, vacía o sólo con nuestros archivos; luego sólo para las tres.
if (Test-Path -LiteralPath $Destino) {
  if (-not (Test-Path -LiteralPath $Destino -PathType Container)) { throw "$Destino existe y no es una carpeta" }
  $otros = @(Get-ChildItem -LiteralPath $Destino -Force | Where-Object { $archivos -notcontains $_.Name })
  if ($otros.Count -gt 0) {
    throw ("$Destino ya tiene otras cosas (" + (($otros | Select-Object -First 3 | ForEach-Object { $_.Name }) -join ', ') +
      "): elige una carpeta nueva o vacía. Le voy a quitar el acceso a todos menos a ti, SYSTEM y Administradores.")
  }
} else {
  New-Item -ItemType Directory -Path $Destino | Out-Null
}
$Destino = (Resolve-Path -LiteralPath $Destino).Path   # ruta completa: .NET (WriteAllText) no conoce la carpeta actual de PowerShell
try {
  Restringir $Destino -Carpeta
} catch {
  throw "No pude dejar $Destino sólo para ti, SYSTEM y Administradores: $($_.Exception.Message). No exporté nada."
}
$ajenos = @(Ajenos $Destino)
if ($ajenos.Count -gt 0) { throw "$Destino sigue abierta a otros ($($ajenos -join '; ')). No exporté nada." }

# 2) El certificado: reusa el que ya exista (con llave privada y vigente); si no, crea uno de firma de código válido 2 años.
$certificado = @(Get-ChildItem -LiteralPath 'Cert:\CurrentUser\My' |
    Where-Object { $_.Subject -eq $Sujeto -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) } |
    Sort-Object NotAfter -Descending) | Select-Object -First 1
if ($certificado) {
  Write-Host "Ya existía en CurrentUser\My: $($certificado.Thumbprint) (vence $($certificado.NotAfter.ToString('yyyy-MM-dd')))"
} else {
  $certificado = New-SelfSignedCertificate -Type Custom -Subject $Sujeto -KeyUsage DigitalSignature `
    -FriendlyName 'Avoqado POS (prueba MSIX)' -CertStoreLocation 'Cert:\CurrentUser\My' `
    -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}') -NotAfter (Get-Date).AddYears(2)
  Write-Host "Creado en CurrentUser\My: $($certificado.Thumbprint)"
}

# 3) Los archivos (nacen dentro de la carpeta cerrada); después, cada secreto con su propia ACL y comprobada.
#    Si algo falla o alguien más puede entrar, se borran el pfx y la clave y truena.
$clave = [guid]::NewGuid().ToString()
$rutaPfx = Join-Path $Destino 'prueba.pfx'
$rutaClave = Join-Path $Destino 'prueba-clave.txt'
$rutaCer = Join-Path $Destino 'prueba.cer'
$secretos = @($rutaPfx, $rutaClave)
try {
  # Un pfx o una clave de una corrida anterior pudo quedar con permisos ABIERTOS: sobrescribirlo escribiría el secreto
  # nuevo en un archivo que otros ya pueden leer (Codex, ronda 2). Se borran primero, para que los nuevos NAZCAN con la
  # ACL cerrada que heredan de la carpeta; si no se pueden borrar, no se escribe nada.
  foreach ($secreto in $secretos) {
    if (Test-Path -LiteralPath $secreto) { Remove-Item -LiteralPath $secreto -Force -ErrorAction Stop }
    if (Test-Path -LiteralPath $secreto) { throw "no pude borrar el $secreto anterior; no escribo secretos encima" }
  }
  [IO.File]::WriteAllText($rutaClave, $clave)   # sin salto de línea; se lee tal cual para -ClavePfx
  Export-Certificate -Cert $certificado -FilePath $rutaCer | Out-Null
  Export-PfxCertificate -Cert $certificado -FilePath $rutaPfx -Password (ConvertTo-SecureString -String $clave -AsPlainText -Force) | Out-Null
  foreach ($secreto in $secretos) {
    Restringir $secreto
    $ajenos = @(Ajenos $secreto)
    if ($ajenos.Count -gt 0) { throw "$secreto quedó abierto a otros ($($ajenos -join '; '))" }
  }
} catch {
  $motivo = $_.Exception.Message
  $quedan = @()
  foreach ($secreto in $secretos) {
    if (Test-Path -LiteralPath $secreto) {
      try { Remove-Item -LiteralPath $secreto -Force } catch { $quedan += $secreto }
    }
  }
  if ($quedan.Count -gt 0) { throw "No dejé el pfx ni su clave: $motivo. NO pude borrar $($quedan -join ', '): bórralos a mano YA." }
  throw "No dejé el pfx ni su clave: $motivo. Borré los dos archivos."
}
Remove-Variable clave

Import-Certificate -FilePath $rutaCer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
Write-Host "Windows ya confía en él (LocalMachine\TrustedPeople). pfx: $rutaPfx · clave: $rutaClave (los dos, sólo para ti, SYSTEM y Administradores)"
Write-Host "Para quitarlo: certificado-de-prueba.ps1 -Destino $Destino -Quitar"
Write-Host "EDITOR=$Sujeto"
