#!/bin/bash
# Network throttle for PAX testing using macOS dnctl + pfctl
# Throttles ALL traffic between the PAX device and this Mac.
# Requires Charles Proxy running as pass-through (throttle OFF in Charles).
#
# Usage:
#   sudo ./scripts/network-throttle.sh 3g-slow
#   sudo ./scripts/network-throttle.sh off
#   sudo ./scripts/network-throttle.sh status
#
# Requires sudo for pfctl/dnctl

set -e

PAX_IP="192.168.100.81"
PF_ANCHOR="avoqado_throttle"
PIPE_IN=100
PIPE_OUT=200

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

show_status() {
    echo -e "${CYAN}=== Network Throttle Status ===${NC}"
    echo ""
    local rules
    rules=$(sudo pfctl -a "$PF_ANCHOR" -s rules 2>/dev/null || true)
    if [ -n "$rules" ]; then
        echo -e "${YELLOW}ACTIVE${NC} - Rules:"
        echo "$rules"
        echo ""
        echo "Pipes:"
        sudo dnctl list 2>/dev/null | grep -E "^[0-9]" || echo "  (no pipes)"
    else
        echo -e "${GREEN}OFF${NC} - No throttle rules active"
    fi
    echo ""
    echo "PAX IP: $PAX_IP"
}

stop_throttle() {
    echo -e "${GREEN}Stopping throttle...${NC}"
    sudo pfctl -a "$PF_ANCHOR" -F all 2>/dev/null || true
    sudo dnctl pipe "$PIPE_IN" delete 2>/dev/null || true
    sudo dnctl pipe "$PIPE_OUT" delete 2>/dev/null || true
    echo -e "${GREEN}Throttle OFF - full speed${NC}"
}

start_throttle() {
    local delay_ms=$1
    local bw_kbps=$2
    local profile=$3

    echo -e "${YELLOW}Setting up throttle: ${profile}${NC}"
    echo "  Delay: ${delay_ms}ms per direction"
    echo "  Bandwidth: ${bw_kbps}Kbit/s (0 = unlimited)"
    echo "  Target: PAX at $PAX_IP"

    stop_throttle 2>/dev/null

    if [ "$bw_kbps" -gt 0 ]; then
        sudo dnctl pipe "$PIPE_IN" config delay "${delay_ms}" bw "${bw_kbps}Kbit/s"
        sudo dnctl pipe "$PIPE_OUT" config delay "${delay_ms}" bw "${bw_kbps}Kbit/s"
    else
        sudo dnctl pipe "$PIPE_IN" config delay "${delay_ms}"
        sudo dnctl pipe "$PIPE_OUT" config delay "${delay_ms}"
    fi

    sudo pfctl -E 2>/dev/null || true

    local rules="dummynet in from ${PAX_IP} to any pipe ${PIPE_IN}
dummynet out from any to ${PAX_IP} pipe ${PIPE_OUT}"

    # Ensure anchor exists in main ruleset
    local has_anchor
    has_anchor=$(sudo pfctl -s rules 2>/dev/null | grep "$PF_ANCHOR" || true)
    if [ -z "$has_anchor" ]; then
        (sudo pfctl -s rules 2>/dev/null; echo "dummynet-anchor \"$PF_ANCHOR\""; echo "anchor \"$PF_ANCHOR\"") | sudo pfctl -f - 2>/dev/null || true
    fi

    echo "$rules" | sudo pfctl -a "$PF_ANCHOR" -f - 2>/dev/null

    echo ""
    echo -e "${YELLOW}Throttle ACTIVE: ${profile}${NC}"
    echo "  Round-trip added: ~$((delay_ms * 2))ms"
    echo ""
    echo "  Change:  sudo ./scripts/network-throttle.sh [profile]"
    echo "  Stop:    sudo ./scripts/network-throttle.sh off"
}

case "${1:-help}" in
    off|none|stop)
        stop_throttle
        ;;
    3g)
        start_throttle 1000 384 "3G (1s delay, 384kbps)"
        ;;
    3g-slow)
        start_throttle 1750 200 "3G Slow (1.75s delay, 200kbps)"
        ;;
    edge)
        start_throttle 2500 56 "EDGE (2.5s delay, 56kbps)"
        ;;
    2g)
        start_throttle 4000 30 "2G (4s delay, 30kbps)"
        ;;
    slow)
        start_throttle 2500 0 "Slow latency (2.5s delay, full bw)"
        ;;
    very-slow)
        start_throttle 5000 0 "Very slow (5s delay, full bw)"
        ;;
    custom)
        if [ -z "$2" ] || [ -z "$3" ]; then
            echo "Usage: sudo ./scripts/network-throttle.sh custom <delay_ms> <bw_kbps>"
            echo "  bw_kbps=0 means unlimited bandwidth"
            echo "Example: sudo ./scripts/network-throttle.sh custom 3000 100"
            exit 1
        fi
        start_throttle "$2" "$3" "Custom (${2}ms, ${3}kbps)"
        ;;
    status)
        show_status
        ;;
    *)
        echo "PAX Network Throttle (targets PAX at $PAX_IP via Charles proxy)"
        echo ""
        echo "Usage: sudo ./scripts/network-throttle.sh [profile]"
        echo ""
        echo "Profiles:"
        echo "  off / stop            Full speed"
        echo "  3g                    ~2s RTT, 384kbps"
        echo "  3g-slow               ~3.5s RTT, 200kbps"
        echo "  edge                  ~5s RTT, 56kbps"
        echo "  2g                    ~8s RTT, 30kbps"
        echo "  slow                  ~5s RTT, full bandwidth"
        echo "  very-slow             ~10s RTT, full bandwidth"
        echo "  custom <ms> <kbps>    Custom (0 kbps = unlimited bw)"
        echo "  status                Show current state"
        ;;
esac
