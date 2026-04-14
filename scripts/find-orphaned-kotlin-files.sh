#!/bin/bash

# Script para detectar archivos Kotlin huérfanos (sin referencias)
# Uso: ./scripts/find-orphaned-kotlin-files.sh

echo "🔍 Buscando archivos Kotlin huérfanos..."
echo ""

ORPHANED_COUNT=0
TOTAL_COUNT=0

# Buscar todos los archivos Kotlin
while IFS= read -r file; do
    TOTAL_COUNT=$((TOTAL_COUNT + 1))

    # Extraer el nombre de la clase del archivo
    class_name=$(basename "$file" .kt)

    # Archivos especiales que siempre se usan (no buscar referencias)
    if [[ "$class_name" == "MainActivity" ]] ||
       [[ "$class_name" == "AvoqadoApp" ]] ||
       [[ "$class_name" == *"Module" ]] ||
       [[ "$file" == *"/di/"* ]] ||
       [[ "$file" == *"/theme/"* ]]; then
        continue
    fi

    # Buscar referencias al nombre de la clase en todo el proyecto
    # Excluir el archivo mismo y archivos de build
    references=$(grep -r "$class_name" app/src/ \
        --include="*.kt" \
        --exclude-dir=build \
        | grep -v "^$file:" \
        | wc -l)

    # Si no hay referencias (o solo 1-2 que pueden ser imports), es huérfano
    if [ "$references" -lt 2 ]; then
        echo "❌ POSIBLE HUÉRFANO: $file"
        echo "   Referencias encontradas: $references"
        echo ""
        ORPHANED_COUNT=$((ORPHANED_COUNT + 1))
    fi

done < <(find app/src/main/java -name "*.kt" -type f)

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 Resumen:"
echo "   Total de archivos Kotlin: $TOTAL_COUNT"
echo "   Posibles huérfanos: $ORPHANED_COUNT"
echo ""

if [ "$ORPHANED_COUNT" -eq 0 ]; then
    echo "✅ No se encontraron archivos huérfanos"
else
    echo "⚠️  Revisa manualmente los archivos marcados antes de eliminarlos"
fi
