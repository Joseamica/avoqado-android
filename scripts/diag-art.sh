#!/usr/bin/env bash
# TEMPORAL: diagnóstico de dex2oat en el emulador de GitHub. Se borra antes del PR.
APK=$1
set -x
adb shell 'getprop ro.build.type; getprop ro.product.cpu.abilist; id; getenforce; ls -l /apex/com.android.art/bin/'
T=/data/local/tmp/d.apk
adb push "$APK" $T
D=/apex/com.android.art/bin/dex2oat64
run() { echo "===== $1"; adb logcat -c; adb shell "$2; echo rc=\$?" 2>&1 | tail -40; sleep 2; adb logcat -d -b all | grep -iE 'dex2oat|verif|avc' | tail -40; }
run plain "$D --dex-file=$T --dex-location=$T --oat-file=/data/local/tmp/d.odex --compiler-filter=verify -j4 2>&1"
run tags "ANDROID_LOG_TAGS='*:v' $D --dex-file=$T --dex-location=$T --oat-file=/data/local/tmp/d.odex --compiler-filter=verify -j4 2>&1"
run stderr "$D --runtime-arg -Xuse-stderr-logger --dex-file=$T --dex-location=$T --oat-file=/data/local/tmp/d.odex --compiler-filter=verify -j4 2>&1"
run verbose "$D --runtime-arg -verbose:verifier --runtime-arg -Xuse-stderr-logger --dex-file=$T --dex-location=$T --oat-file=/data/local/tmp/d.odex --compiler-filter=verify -j4 2>&1 | grep -iE 'took|Verification error|failed to verify|error' | head -20"
adb shell ls -l /data/local/tmp/
adb root; sleep 3; adb wait-for-device
adb shell id
run root "$D --dex-file=$T --dex-location=$T --oat-file=/data/local/tmp/d.odex --compiler-filter=verify -j4 2>&1"
PKG=$("$ANDROID_HOME"/build-tools/*/aapt2 dump packagename "$APK" 2>/dev/null | head -1)
echo "pkg=$PKG"
adb install -r "$APK"
run compile "cmd package compile -m verify -f $PKG"
adb shell dumpsys package dexopt | grep -A6 "$PKG" | head -20
exit 0
