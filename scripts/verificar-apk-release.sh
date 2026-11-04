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
# -Xuse-stderr-logger: dex2oat escribe sus avisos a su propia salida (medido en el emulador de GitHub)
# y no dependemos de logcat. logcat se sigue leyendo: es donde los deja un aparato real sin la opción.
SALIDA=$("${ADB[@]}" shell "$DEX2OAT --runtime-arg -Xuse-stderr-logger --dex-file=$T --dex-location=$T --oat-file=${T%.apk}.odex --compiler-filter=verify -j4 2>&1; echo dex2oat=\$?; rm -f $T ${T%.apk}.odex ${T%.apk}.vdex" | sed 's/^/dex2oat: /')
sleep 2
LOG=$(printf '%s\n%s\n' "$SALIDA" "$("${ADB[@]}" logcat -d -b all)")
# Here-strings y no `echo | grep -q`: con pipefail, grep -q corta al primer acierto, echo recibe
# SIGPIPE con un logcat grande y la tubería «falla» aunque la línea sí estaba (medido el 4-nov).
grep -q "dex2oat=0" <<<"$SALIDA" || { echo "::error::dex2oat falló:"; echo "$SALIDA" | tail -5; exit 2; }
grep -q "dex2oat took" <<<"$LOG" || { echo "::error::dex2oat no dijo cuánto tardó: no puedo asegurar que revisó el APK."; echo "$SALIDA" | tail -5; exit 2; }
ERR=$(grep -E "dex2oat.*(Verification error|failed to verify)" <<<"$LOG" || true)
if [ -n "$ERR" ]; then
  echo "::error::ART rechaza clases del APK de release (se cerraría al usarlas). Revisa R8 / proguard-rules.pro."
  echo "$ERR"
  exit 1
fi
echo "✅ ART verificó todas las clases de $(basename "$APK")"
