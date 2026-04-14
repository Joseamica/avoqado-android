#!/bin/bash
# Quick throttle profile switcher for mitmproxy
# Usage: ./scripts/throttle.sh [profile]
# Profiles: none, 3g, 3g-slow, edge, 2g, intermittent, slow-latency-only

CONFIG="$(dirname "$0")/throttle-config.json"

case "${1:-help}" in
  none|off)
    cat > "$CONFIG" << 'EOF'
{"enabled": false, "latency_ms": 0, "bandwidth_kbps": 0, "profile": "none", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle OFF - full speed"
    ;;
  3g)
    cat > "$CONFIG" << 'EOF'
{"enabled": true, "latency_ms": 2000, "bandwidth_kbps": 384, "profile": "3g", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle: 3G (2s latency, 384kbps)"
    ;;
  3g-slow)
    cat > "$CONFIG" << 'EOF'
{"enabled": true, "latency_ms": 3500, "bandwidth_kbps": 200, "profile": "3g-slow", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle: 3G Slow (3.5s latency, 200kbps)"
    ;;
  edge)
    cat > "$CONFIG" << 'EOF'
{"enabled": true, "latency_ms": 5000, "bandwidth_kbps": 56, "profile": "edge", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle: EDGE (5s latency, 56kbps)"
    ;;
  2g)
    cat > "$CONFIG" << 'EOF'
{"enabled": true, "latency_ms": 8000, "bandwidth_kbps": 30, "profile": "2g", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle: 2G (8s latency, 30kbps)"
    ;;
  intermittent)
    cat > "$CONFIG" << 'EOF'
{"enabled": true, "latency_ms": 4000, "bandwidth_kbps": 100, "profile": "intermittent", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle: Intermittent (4s latency, 100kbps)"
    ;;
  slow-latency-only)
    cat > "$CONFIG" << 'EOF'
{"enabled": true, "latency_ms": 5000, "bandwidth_kbps": 0, "profile": "slow-latency-only", "log_requests": true, "log_responses": true, "block_hosts": []}
EOF
    echo "Throttle: Slow latency only (5s, full bandwidth)"
    ;;
  status)
    cat "$CONFIG" | python3 -m json.tool
    ;;
  *)
    echo "Usage: ./scripts/throttle.sh [profile]"
    echo ""
    echo "Profiles:"
    echo "  none / off         - No throttle (full speed)"
    echo "  3g                 - 3G (2s latency, 384kbps)"
    echo "  3g-slow            - 3G slow (3.5s latency, 200kbps)"
    echo "  edge               - EDGE (5s latency, 56kbps)"
    echo "  2g                 - 2G (8s latency, 30kbps)"
    echo "  intermittent       - Intermittent (4s latency, 100kbps)"
    echo "  slow-latency-only  - Just slow latency (5s, no bandwidth limit)"
    echo "  status             - Show current config"
    echo ""
    echo "Changes take effect on the NEXT request (no restart needed)"
    ;;
esac
