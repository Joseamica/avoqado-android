#!/bin/bash
# Charles Proxy throttle control via Web Interface API
# Requires: Charles running with Web Interface enabled (Proxy > Web Interface Settings)
#
# Usage:
#   ./scripts/charles-throttle.sh on [preset]    # Activate throttle
#   ./scripts/charles-throttle.sh off             # Deactivate throttle
#   ./scripts/charles-throttle.sh status          # Show current state
#   ./scripts/charles-throttle.sh presets         # List available presets
#
# Presets: 56kbps, 3g, 4g, 256kbps, 512kbps, 2mbps, 8mbps
# Default preset: 56kbps (56 kbps Modem — slowest, most useful for testing)

CHARLES_PROXY="http://127.0.0.1:8888"
CHARLES_API="http://control.charles"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

charles_curl() {
    curl -s -x "$CHARLES_PROXY" "$CHARLES_API/$1" 2>/dev/null
}

check_charles() {
    local result
    result=$(charles_curl "" 2>/dev/null)
    if echo "$result" | grep -q "Web Interface"; then
        return 0
    else
        echo -e "${RED}ERROR: Charles Web Interface not accessible${NC}"
        echo "Make sure Charles is running and Web Interface is enabled"
        echo "(Proxy > Web Interface Settings > Enable web interface + Allow anonymous access)"
        exit 1
    fi
}

resolve_preset() {
    case "${1:-56kbps}" in
        56kbps|modem|56)     echo "56 kbps Modem" ;;
        256kbps|isdn|256)    echo "256 kbps ISDN/DSL" ;;
        512kbps|dsl|512)     echo "512 kbps ISDN/DSL" ;;
        2mbps|adsl|2m)       echo "2 Mbps ADSL" ;;
        8mbps|adsl2|8m)      echo "8 Mbps ADSL2" ;;
        16mbps|16m)          echo "16 Mbps ADSL2+" ;;
        32mbps-vdsl|32v)     echo "32 Mbps VDSL" ;;
        32mbps-fibre|32f)    echo "32 Mbps Fibre" ;;
        100mbps|fibre|100m)  echo "100 Mbps Fibre" ;;
        3g)                  echo "3G" ;;
        4g)                  echo "4G" ;;
        current|"")          echo "" ;;
        *)
            echo -e "${RED}Unknown preset: $1${NC}" >&2
            echo "Run: ./scripts/charles-throttle.sh presets" >&2
            exit 1
            ;;
    esac
}

case "${1:-help}" in
    on|activate|start)
        check_charles
        preset_name=$(resolve_preset "$2")
        if [ -n "$preset_name" ]; then
            encoded=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$preset_name'))")
            result=$(charles_curl "throttling/activate?preset=$encoded")
        else
            result=$(charles_curl "throttling/activate")
        fi
        if echo "$result" | grep -q "Status: Throttling"; then
            echo -e "${YELLOW}Throttle ON${NC}: ${preset_name:-Current settings}"
        else
            echo -e "${RED}Failed to activate throttle${NC}"
        fi
        ;;
    off|deactivate|stop)
        check_charles
        result=$(charles_curl "throttling/deactivate")
        if echo "$result" | grep -q "Stopped"; then
            echo -e "${GREEN}Throttle OFF${NC} — full speed"
        else
            echo -e "${RED}Failed to deactivate throttle${NC}"
        fi
        ;;
    status)
        check_charles
        result=$(charles_curl "throttling/")
        if echo "$result" | grep -q "Status: Throttling"; then
            echo -e "${YELLOW}ACTIVE${NC} — throttling enabled"
        else
            echo -e "${GREEN}OFF${NC} — full speed"
        fi
        ;;
    presets)
        echo "Available presets:"
        echo "  56kbps    56 kbps Modem (slowest — best for testing)"
        echo "  256kbps   256 kbps ISDN/DSL"
        echo "  512kbps   512 kbps ISDN/DSL"
        echo "  2mbps     2 Mbps ADSL"
        echo "  8mbps     8 Mbps ADSL2"
        echo "  16mbps    16 Mbps ADSL2+"
        echo "  3g        3G"
        echo "  4g        4G"
        echo "  current   Use current Charles throttle settings"
        echo ""
        echo "Usage: ./scripts/charles-throttle.sh on [preset]"
        ;;
    *)
        echo "Charles Proxy Throttle Control"
        echo ""
        echo "Usage: ./scripts/charles-throttle.sh [command] [preset]"
        echo ""
        echo "Commands:"
        echo "  on [preset]   Activate throttle (default: 56kbps)"
        echo "  off           Deactivate throttle"
        echo "  status        Show current state"
        echo "  presets       List available presets"
        echo ""
        echo "Examples:"
        echo "  ./scripts/charles-throttle.sh on 56kbps"
        echo "  ./scripts/charles-throttle.sh on 3g"
        echo "  ./scripts/charles-throttle.sh off"
        ;;
esac
