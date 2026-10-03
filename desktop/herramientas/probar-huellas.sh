#!/usr/bin/env bash
# Prueba de las alarmas de paridad: `:pos:verificarExcluidos` y `:pos:actualizarHuellas` (desktop/pos/build.gradle.kts).
# Corre sobre un árbol TEMPORAL que imita a Android (app/src/main/java + AndroidManifest.xml) y un excluidos.txt de
# prueba, pasados por -Pavoqado.paridad.android / -Pavoqado.paridad.excluidos: nunca toca el excluidos.txt real ni app/.
#
# Uso, desde el root del workspace (por la fila, como toda corrida de Gradle):
#   AVQ_ANDROID_MAC=1 AVQ_SIN_REUSO=1 ./scripts/avq-verify.sh avoqado-android bash desktop/herramientas/probar-huellas.sh
# La última línea dice el veredicto: «PRUEBAS-HUELLAS: N bien, M mal».
set -u
cd "$(dirname "$0")/../.." || exit 2   # raíz de avoqado-android

T=$(mktemp -d "${TMPDIR:-/tmp}/huellas.XXXXXX")
trap 'rm -rf "$T"' EXIT
A="$T/android"
mkdir -p "$A/app/src/main/java/com/x"
printf 'class Uno\n' > "$A/app/src/main/java/com/x/Uno.kt"
printf 'class Dos\n' > "$A/app/src/main/java/com/x/Dos.kt"
printf '<manifest/>\n' > "$A/app/src/main/AndroidManifest.xml"
cat > "$T/excluidos.txt" <<'EOF'
# Un comentario que se conserva tal cual.
com/x/Uno.kt   # reemplazo: la nota de Uno

com/x/Dos.kt   # sin reemplazo: la nota de Dos
@manifiesto app/src/main/AndroidManifest.xml
EOF

BIEN=0; MAL=0; SALIDA=""; RC=0
gradle() {   # gradle <tarea>: deja la salida en $SALIDA y el código en $RC
  SALIDA=$(./gradlew -p desktop --console=plain -q "-Pavoqado.paridad.excluidos=$T/excluidos.txt" "-Pavoqado.paridad.android=$A" "$@" 2>&1)
  RC=$?
}
afirmar() {  # afirmar <descripción> <comando de prueba…>
  local que="$1"; shift
  if "$@"; then BIEN=$((BIEN + 1)); echo "  ✅ $que"
  else MAL=$((MAL + 1)); echo "  ❌ $que"; printf '%s\n' "$SALIDA" | sed 's/^/       │ /' | head -40; fi
}
dice()    { printf '%s' "$SALIDA" | grep -qF -- "$1"; }
no_dice() { ! dice "$1"; }
verde()   { [ "$RC" -eq 0 ]; }
rojo()    { [ "$RC" -ne 0 ]; }
huellas() { grep -cE '\| huella: [0-9a-f]{64}$' "$T/excluidos.txt"; }

echo "1) Sin huellas: rojo, nombra a los TRES y pide actualizarHuellas"
gradle :pos:verificarExcluidos
afirmar "rojo" rojo
afirmar "nombra com/x/Uno.kt" dice "com/x/Uno.kt"
afirmar "nombra com/x/Dos.kt" dice "com/x/Dos.kt"
afirmar "nombra el manifiesto" dice "app/src/main/AndroidManifest.xml"
afirmar "dice cómo arreglarlo" dice ":pos:actualizarHuellas"

echo "2) actualizarHuellas: escribe las tres huellas, conserva comentarios, renglón vacío y orden; luego verde"
gradle :pos:actualizarHuellas
afirmar "actualizarHuellas verde" verde
afirmar "tres huellas de 64 hex" [ "$(huellas)" = 3 ]
afirmar "el comentario sigue" grep -qxF '# Un comentario que se conserva tal cual.' "$T/excluidos.txt"
afirmar "la nota sigue" grep -qE '^com/x/Uno\.kt   # reemplazo: la nota de Uno \| huella: [0-9a-f]{64}$' "$T/excluidos.txt"
afirmar "el manifiesto con su huella" grep -qE '^@manifiesto app/src/main/AndroidManifest\.xml \| huella: [0-9a-f]{64}$' "$T/excluidos.txt"
afirmar "mismo número de renglones (5)" [ "$(wc -l < "$T/excluidos.txt" | tr -d ' ')" = 5 ]
afirmar "el orden no cambió" [ "$(grep -nF 'com/x/Dos.kt' "$T/excluidos.txt" | cut -d: -f1)" = 4 ]
afirmar "la huella es el SHA-256 del contenido" grep -qF "huella: $(shasum -a 256 "$A/app/src/main/java/com/x/Uno.kt" | cut -c1-64)" "$T/excluidos.txt"
gradle :pos:verificarExcluidos
afirmar "verificarExcluidos verde" verde
cp "$T/excluidos.txt" "$T/excluidos.bueno"

echo "3) Android cambia un byte de Uno.kt y el manifiesto: rojo, nombra a LOS DOS (no a Dos.kt) y qué hacer"
printf 'class Uno2\n' > "$A/app/src/main/java/com/x/Uno.kt"
printf '<manifest a="1"/>\n' > "$A/app/src/main/AndroidManifest.xml"
gradle :pos:verificarExcluidos
afirmar "rojo" rojo
afirmar "dice que Android cambió Uno.kt" dice "Android cambió com/x/Uno.kt"
afirmar "dice que Android cambió el manifiesto" dice "Android cambió app/src/main/AndroidManifest.xml"
afirmar "no acusa a Dos.kt" no_dice "Android cambió com/x/Dos.kt"
afirmar "da el git log del archivo" dice "git log -p -- app/src/main/java/com/x/Uno.kt"
afirmar "repite la nota del reemplazo" dice "la nota de Uno"
afirmar "pide actualizarHuellas" dice ":pos:actualizarHuellas"

echo "4) El MISMO contenido con fin de línea CRLF: verde (la huella es del contenido normalizado a LF)"
printf 'class Uno\r\n' > "$A/app/src/main/java/com/x/Uno.kt"
printf '<manifest/>\r\n' > "$A/app/src/main/AndroidManifest.xml"
gradle :pos:verificarExcluidos
afirmar "verde con CRLF" verde

echo "5) Un renglón sin huella (uno nuevo, o uno viejo): rojo, nunca pasa en silencio"
printf 'class Uno\n' > "$A/app/src/main/java/com/x/Uno.kt"
sed -E 's#^(com/x/Dos\.kt.*) \| huella: [0-9a-f]{64}$#\1#' "$T/excluidos.bueno" > "$T/excluidos.txt"
gradle :pos:verificarExcluidos
afirmar "rojo" rojo
afirmar "nombra com/x/Dos.kt sin huella" dice "com/x/Dos.kt no tiene huella"
afirmar "pide actualizarHuellas" dice ":pos:actualizarHuellas"
printf 'com/x/Tres.kt\n' >> "$T/excluidos.txt"
printf 'class Tres\n' > "$A/app/src/main/java/com/x/Tres.kt"
gradle :pos:verificarExcluidos
afirmar "un renglón nuevo sin comentario también: rojo" rojo
afirmar "nombra com/x/Tres.kt sin huella" dice "com/x/Tres.kt no tiene huella"

echo "6) actualizarHuellas otra vez: verde (y el renglón sin comentario recibe su huella)"
gradle :pos:actualizarHuellas
afirmar "actualizarHuellas verde" verde
afirmar "cuatro huellas" [ "$(huellas)" = 4 ]
gradle :pos:verificarExcluidos
afirmar "verificarExcluidos verde" verde

echo "7) Un archivo vigilado que ya no existe: rojo y lo dice"
rm "$A/app/src/main/java/com/x/Tres.kt"
gradle :pos:verificarExcluidos
afirmar "rojo" rojo
afirmar "dice que ya no existe" dice "ya no existen"
afirmar "nombra com/x/Tres.kt" dice "com/x/Tres.kt"
gradle :pos:actualizarHuellas
afirmar "actualizarHuellas tampoco inventa una huella: rojo" rojo
printf 'class Tres\n' > "$A/app/src/main/java/com/x/Tres.kt"

echo "8) Una directiva que no existe (@otra): rojo, no se ignora"
printf '@otra app/src/main/AndroidManifest.xml\n' >> "$T/excluidos.txt"
gradle :pos:verificarExcluidos
afirmar "rojo" rojo
afirmar "nombra la directiva" dice "@otra"

echo "PRUEBAS-HUELLAS: $BIEN bien, $MAL mal"
[ "$MAL" -eq 0 ]
