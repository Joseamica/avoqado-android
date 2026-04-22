#!/usr/bin/env bash
set -euo pipefail

# Quick responsive testing helper for Android emulators/devices.
# Usage examples:
#   ./scripts/adaptive-check.sh compact
#   ./scripts/adaptive-check.sh medium --serial emulator-5554
#   ./scripts/adaptive-check.sh expanded
#   ./scripts/adaptive-check.sh portrait
#   ./scripts/adaptive-check.sh landscape
#   ./scripts/adaptive-check.sh font 1.3
#   ./scripts/adaptive-check.sh status
#   ./scripts/adaptive-check.sh reset

SCRIPT_NAME="$(basename "$0")"

print_usage() {
  cat <<EOF
Usage: ./$SCRIPT_NAME <command> [args] [--serial <device-id>]

Commands:
  compact                  Apply compact profile (phone)
  medium                   Apply medium profile
  expanded                 Apply expanded profile (tablet-ish)
  portrait                 Force portrait orientation
  landscape                Force landscape orientation
  auto-rotate              Re-enable automatic rotation
  font <scale>             Set font scale (examples: 0.85, 1.0, 1.3, 1.5)
  status                   Show current wm size, density, and font scale
  reset                    Reset wm size, density and font scale (1.0)

Options:
  --serial <device-id>     Target specific emulator/device
  -h, --help               Show this help
EOF
}

COMMAND="${1:-}"
if [[ -z "$COMMAND" || "$COMMAND" == "-h" || "$COMMAND" == "--help" ]]; then
  print_usage
  exit 0
fi
shift || true

TARGET_SERIAL=""
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      TARGET_SERIAL="${2:-}"
      if [[ -z "$TARGET_SERIAL" ]]; then
        echo "Error: --serial requires a value." >&2
        exit 1
      fi
      shift 2
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb is not installed or not in PATH." >&2
  exit 1
fi

if [[ -z "$TARGET_SERIAL" ]]; then
  TARGET_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi

if [[ -z "$TARGET_SERIAL" ]]; then
  echo "Error: no connected Android device/emulator found." >&2
  echo "Tip: start an emulator or connect a device, then run again." >&2
  exit 1
fi

adb_shell() {
  adb -s "$TARGET_SERIAL" shell "$@"
}

set_profile() {
  local size="$1"
  local density="$2"
  adb_shell wm size "$size"
  adb_shell wm density "$density"
}

set_font_scale() {
  local scale="$1"
  adb_shell settings put system font_scale "$scale"
}

show_status() {
  echo "Device: $TARGET_SERIAL"
  echo "wm size:"
  adb_shell wm size
  echo "wm density:"
  adb_shell wm density
  echo "font_scale:"
  adb_shell settings get system font_scale
  echo "accelerometer_rotation:"
  adb_shell settings get system accelerometer_rotation
  echo "user_rotation:"
  adb_shell settings get system user_rotation
}

set_portrait() {
  adb_shell settings put system accelerometer_rotation 0
  adb_shell settings put system user_rotation 0
}

set_landscape() {
  adb_shell settings put system accelerometer_rotation 0
  adb_shell settings put system user_rotation 1
}

set_auto_rotate() {
  adb_shell settings put system accelerometer_rotation 1
}

case "$COMMAND" in
  compact)
    set_profile "1080x2400" "440"
    echo "Applied COMPACT profile on $TARGET_SERIAL"
    show_status
    ;;
  medium)
    set_profile "1600x2560" "320"
    echo "Applied MEDIUM profile on $TARGET_SERIAL"
    show_status
    ;;
  expanded)
    set_profile "2048x2732" "320"
    echo "Applied EXPANDED profile on $TARGET_SERIAL"
    show_status
    ;;
  portrait)
    set_portrait
    echo "Forced PORTRAIT orientation on $TARGET_SERIAL"
    show_status
    ;;
  landscape)
    set_landscape
    echo "Forced LANDSCAPE orientation on $TARGET_SERIAL"
    show_status
    ;;
  auto-rotate)
    set_auto_rotate
    echo "Enabled AUTO-ROTATE on $TARGET_SERIAL"
    show_status
    ;;
  font)
    SCALE="${EXTRA_ARGS[0]:-}"
    if [[ -z "$SCALE" ]]; then
      echo "Error: font command requires a scale value." >&2
      echo "Example: ./$SCRIPT_NAME font 1.3" >&2
      exit 1
    fi
    set_font_scale "$SCALE"
    echo "Applied font_scale=$SCALE on $TARGET_SERIAL"
    show_status
    ;;
  status)
    show_status
    ;;
  reset)
    adb_shell wm size reset
    adb_shell wm density reset
    set_font_scale "1.0"
    set_auto_rotate
    echo "Reset display overrides on $TARGET_SERIAL"
    show_status
    ;;
  *)
    echo "Error: unknown command '$COMMAND'" >&2
    print_usage
    exit 1
    ;;
esac
