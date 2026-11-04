#!/usr/bin/env bash
# Pasa un APK por el verificador de ART (dex2oat) de un aparato o emulador conectado por adb.
# Sale 1 si alguna clase no verifica.
#
# Por qué existe (4-nov-2026): la 2.25.30 pasó pruebas y CI y aun así se cerraba al imprimir. R8
# dejó `TableOrderScreen(...)` con bytecode que ART rechaza (VerifyError) y la clase entera quedó
# inservible. Las pruebas corren la versión SIN optimizar, así que nada lo vio. dex2oat avisa
# «Verification error in …» en logcat en ~1 s, sin abrir pantallas (medido en la D3: la 2.25.30
# sale ❌ y la 2.25.31 ✅).
#
# Uso: scripts/verificar-apk-release.sh <apk> [serial]
set -euo pipefail
APK=$1; S=${2:-}
ADB=(adb); [ -n "$S" ] && ADB=(adb -s "$S")
T=/data/local/tmp/verificar-$$.apk
DEX2OAT=$("${ADB[@]}" shell 'ls /apex/com.android.art/bin/dex2oat64 2>/dev/null || ls /apex/com.android.art/bin/dex2oat32' | tr -d '\r')
[ -n "$DEX2OAT" ] || { echo "::error::No encontré dex2oat en el aparato."; exit 2; }
"${ADB[@]}" push "$APK" "$T" >/dev/null
"${ADB[@]}" logcat -c
"${ADB[@]}" shell "$DEX2OAT --dex-file=$T --dex-location=$T --oat-file=${T%.apk}.odex --compiler-filter=verify -j4 >/dev/null 2>&1; echo dex2oat=\$?; rm -f $T ${T%.apk}.odex ${T%.apk}.vdex"
sleep 2
LOG=$("${ADB[@]}" logcat -d)
echo "$LOG" | grep -q "dex2oat.*took" || { echo "::error::dex2oat no terminó: no puedo decir si el APK está sano."; exit 2; }
ERR=$(echo "$LOG" | grep -E "dex2oat.*(Verification error|failed to verify)" || true)
if [ -n "$ERR" ]; then
  echo "::error::ART rechaza clases del APK de release (se cerraría al usarlas). Revisa R8 / proguard-rules.pro."
  echo "$ERR"
  exit 1
fi
echo "✅ ART verificó todas las clases de $(basename "$APK")"
