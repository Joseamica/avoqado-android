#!/bin/bash
# Cross-repo consistency check before TPV release
#
# This script verifies that all three Avoqado repos are in sync
# before generating a production APK.
#
# Usage: ./scripts/check-cross-repo.sh
#
# Exit codes:
#   0 = All checks passed
#   1 = Critical error (should NOT proceed with release)
#   2 = Warnings only (can proceed but review carefully)

echo "🔍 Verificando consistencia cross-repo..."
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ERRORS=0
WARNINGS=0

# Paths to sibling repos
SERVER_PATH="../avoqado-server"
DASHBOARD_PATH="../avoqado-web-dashboard"

# ═══════════════════════════════════════════════════════════════
# 1. Verify sibling repos exist
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}📂 [1/6] Verificando repositorios...${NC}"

if [ ! -d "$SERVER_PATH" ]; then
    echo -e "${RED}   ❌ avoqado-server no encontrado en $SERVER_PATH${NC}"
    ERRORS=$((ERRORS+1))
else
    echo -e "${GREEN}   ✓ avoqado-server encontrado${NC}"
fi

if [ ! -d "$DASHBOARD_PATH" ]; then
    echo -e "${RED}   ❌ avoqado-web-dashboard no encontrado en $DASHBOARD_PATH${NC}"
    ERRORS=$((ERRORS+1))
else
    echo -e "${GREEN}   ✓ avoqado-web-dashboard encontrado${NC}"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 2. Check for uncommitted changes (BLOCKING)
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}📝 [2/6] Verificando cambios no commiteados...${NC}"

TPV_DIRTY=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
SERVER_DIRTY=$(git -C "$SERVER_PATH" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
DASHBOARD_DIRTY=$(git -C "$DASHBOARD_PATH" status --porcelain 2>/dev/null | wc -l | tr -d ' ')

if [ "$TPV_DIRTY" -gt 0 ]; then
    echo -e "${RED}   ❌ avoqado-tpv tiene $TPV_DIRTY cambios sin commitear${NC}"
    ERRORS=$((ERRORS+1))
else
    echo -e "${GREEN}   ✓ avoqado-tpv limpio${NC}"
fi

if [ "$SERVER_DIRTY" -gt 0 ]; then
    echo -e "${YELLOW}   ⚠️  avoqado-server tiene $SERVER_DIRTY cambios sin commitear${NC}"
    WARNINGS=$((WARNINGS+1))
else
    echo -e "${GREEN}   ✓ avoqado-server limpio${NC}"
fi

if [ "$DASHBOARD_DIRTY" -gt 0 ]; then
    echo -e "${YELLOW}   ⚠️  avoqado-web-dashboard tiene $DASHBOARD_DIRTY cambios sin commitear${NC}"
    WARNINGS=$((WARNINGS+1))
else
    echo -e "${GREEN}   ✓ avoqado-web-dashboard limpio${NC}"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 3. Check for unpushed commits (BLOCKING for TPV)
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}🚀 [3/6] Verificando commits pusheados...${NC}"

TPV_UNPUSHED=$(git log origin/main..HEAD --oneline 2>/dev/null | wc -l | tr -d ' ')
SERVER_UNPUSHED=$(git -C "$SERVER_PATH" log origin/develop..HEAD --oneline 2>/dev/null | wc -l | tr -d ' ')
DASHBOARD_UNPUSHED=$(git -C "$DASHBOARD_PATH" log origin/develop..HEAD --oneline 2>/dev/null | wc -l | tr -d ' ')

if [ "$TPV_UNPUSHED" -gt 0 ]; then
    echo -e "${RED}   ❌ avoqado-tpv tiene $TPV_UNPUSHED commits sin pushear${NC}"
    ERRORS=$((ERRORS+1))
else
    echo -e "${GREEN}   ✓ avoqado-tpv pusheado${NC}"
fi

if [ "$SERVER_UNPUSHED" -gt 0 ]; then
    echo -e "${YELLOW}   ⚠️  avoqado-server tiene $SERVER_UNPUSHED commits sin pushear${NC}"
    WARNINGS=$((WARNINGS+1))
else
    echo -e "${GREEN}   ✓ avoqado-server pusheado${NC}"
fi

if [ "$DASHBOARD_UNPUSHED" -gt 0 ]; then
    echo -e "${YELLOW}   ⚠️  avoqado-web-dashboard tiene $DASHBOARD_UNPUSHED commits sin pushear${NC}"
    WARNINGS=$((WARNINGS+1))
else
    echo -e "${GREEN}   ✓ avoqado-web-dashboard pusheado${NC}"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 4. Check backend is online
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}🌐 [4/6] Verificando backend en producción...${NC}"

HEALTH_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "https://api.avoqado.io/health" 2>/dev/null)

if [ "$HEALTH_RESPONSE" = "200" ]; then
    echo -e "${GREEN}   ✓ api.avoqado.io está online (HTTP 200)${NC}"
else
    echo -e "${RED}   ❌ api.avoqado.io no responde (HTTP $HEALTH_RESPONSE)${NC}"
    ERRORS=$((ERRORS+1))
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 5. Show recent commits for context
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}📦 [5/6] Últimos commits por repo:${NC}"
echo ""

echo -e "${YELLOW}   avoqado-tpv (este repo):${NC}"
git --no-pager log --oneline -3 2>/dev/null | sed 's/^/      /'
echo ""

echo -e "${YELLOW}   avoqado-server:${NC}"
git --no-pager -C "$SERVER_PATH" log --oneline -3 2>/dev/null | sed 's/^/      /' || echo "      (no disponible)"
echo ""

echo -e "${YELLOW}   avoqado-web-dashboard:${NC}"
git --no-pager -C "$DASHBOARD_PATH" log --oneline -3 2>/dev/null | sed 's/^/      /' || echo "      (no disponible)"
echo ""

# ═══════════════════════════════════════════════════════════════
# 6. Show TPV version
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}📱 [6/6] Versión del TPV:${NC}"

VERSION=$(grep "versionName" app/build.gradle.kts 2>/dev/null | head -1 | sed 's/.*"\(.*\)".*/\1/')
VERSION_CODE=$(grep "versionCode" app/build.gradle.kts 2>/dev/null | head -1 | sed 's/[^0-9]*//g')

if [ -n "$VERSION" ]; then
    echo -e "${GREEN}   📱 Version: $VERSION (code: $VERSION_CODE)${NC}"
else
    echo -e "${YELLOW}   ⚠️  No se pudo leer la versión${NC}"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# FINAL RESULT
# ═══════════════════════════════════════════════════════════════
echo "═══════════════════════════════════════════════════════════"

if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}❌ BLOQUEADO: $ERRORS error(es) crítico(s)${NC}"
    echo ""
    echo "   No se debe generar APK de producción hasta resolver:"
    [ "$TPV_DIRTY" -gt 0 ] && echo "   • Commitear cambios en avoqado-tpv"
    [ "$TPV_UNPUSHED" -gt 0 ] && echo "   • Pushear commits de avoqado-tpv"
    [ "$HEALTH_RESPONSE" != "200" ] && echo "   • Verificar que el backend esté online"
    echo ""
    echo "═══════════════════════════════════════════════════════════"
    exit 1
elif [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}⚠️  ADVERTENCIAS: $WARNINGS advertencia(s)${NC}"
    echo ""
    echo "   Puedes continuar, pero verifica manualmente:"
    [ "$SERVER_DIRTY" -gt 0 ] && echo "   • ¿Los cambios en avoqado-server afectan al TPV?"
    [ "$DASHBOARD_DIRTY" -gt 0 ] && echo "   • ¿Los cambios en dashboard afectan al TPV?"
    [ "$SERVER_UNPUSHED" -gt 0 ] && echo "   • ¿El TPV requiere los commits no pusheados del server?"
    [ "$DASHBOARD_UNPUSHED" -gt 0 ] && echo "   • ¿El TPV requiere los commits no pusheados del dashboard?"
    echo ""
    echo "═══════════════════════════════════════════════════════════"
    exit 2
else
    echo -e "${GREEN}✅ TODO LISTO PARA RELEASE${NC}"
    echo ""
    echo "   Verificaciones pasadas:"
    echo "   • Todos los repos sin cambios pendientes"
    echo "   • Todos los commits pusheados"
    echo "   • Backend online en producción"
    echo ""
    echo "═══════════════════════════════════════════════════════════"
    exit 0
fi
