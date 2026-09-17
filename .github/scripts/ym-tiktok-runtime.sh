#!/usr/bin/env bash
set -euo pipefail

mkdir -p runtime/evidence runtime/tiktok/unpacked

YM_APK="ym-ci/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$YM_APK" ]]; then
  echo "YM APK missing: $YM_APK" >&2
  exit 10
fi

XAPK="$(find runtime/tiktok -maxdepth 2 -type f \( -name '*.xapk' -o -name '*.apks' -o -name '*.zip' \) | head -n1 || true)"
if [[ -n "$XAPK" ]]; then
  unzip -q -o "$XAPK" -d runtime/tiktok/unpacked
fi

mapfile -t TIKTOK_APKS < <(find runtime/tiktok -type f -name '*.apk' | sort)
if [[ ${#TIKTOK_APKS[@]} -eq 0 ]]; then
  echo "No TikTok APK files were downloaded" >&2
  find runtime/tiktok -maxdepth 3 -type f -ls || true
  exit 11
fi

adb wait-for-device
adb shell getprop ro.build.version.sdk | tee runtime/evidence/android-api.txt
adb shell getprop ro.product.cpu.abilist | tee runtime/evidence/emulator-abis.txt

adb install -r "$YM_APK"

if [[ ${#TIKTOK_APKS[@]} -eq 1 ]]; then
  adb install -r "${TIKTOK_APKS[0]}"
else
  adb install-multiple -r "${TIKTOK_APKS[@]}"
fi

adb shell pm path com.zhiliaoapp.musically | tee runtime/evidence/tiktok-package-path.txt
if ! grep -q 'package:' runtime/evidence/tiktok-package-path.txt; then
  echo "TikTok official package was not installed" >&2
  exit 12
fi

adb shell dumpsys package com.zhiliaoapp.musically > runtime/evidence/tiktok-package.txt
adb shell dumpsys package com.ym.lite.stable > runtime/evidence/ym-package.txt

cat > runtime/ym_auto_comment.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enabled" value="true" />
    <int name="min_interval_seconds" value="3" />
</map>
XML
adb push runtime/ym_auto_comment.xml /data/local/tmp/ym_auto_comment.xml
adb shell 'run-as com.ym.lite.stable mkdir -p shared_prefs && cp /data/local/tmp/ym_auto_comment.xml shared_prefs/ym_auto_comment.xml'

adb shell appops set com.ym.lite.stable SYSTEM_ALERT_WINDOW allow || true
adb shell settings put secure enabled_accessibility_services com.ym.lite.stable/com.ym.lite.automation.YmTikTokAccessibilityService
adb shell settings put secure accessibility_enabled 1

adb shell am start-foreground-service -n com.ym.lite.stable/com.ym.lite.overlay.YmOverlayService || true
adb shell monkey -p com.zhiliaoapp.musically -c android.intent.category.LAUNCHER 1
sleep 18

for i in 1 2 3 4 5 6; do
  echo "=== cycle $i ==="
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "runtime/evidence/ui-$i.xml" >/dev/null 2>&1 || true
  adb exec-out screencap -p > "runtime/evidence/screen-$i.png" || true
  adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' > "runtime/evidence/activity-$i.txt" || true
  adb shell dumpsys accessibility > "runtime/evidence/accessibility-$i.txt" || true
  adb shell 'run-as com.ym.lite.stable cat shared_prefs/ym_local.xml' > "runtime/evidence/ym-local-$i.xml" 2>/dev/null || true
  grep -Eio 'comment|comments|add comment|write comment|send|post|تعليق|تعليقات|إرسال|نشر' "runtime/evidence/ui-$i.xml" | sort -u > "runtime/evidence/comment-tokens-$i.txt" || true
  if [[ $i -lt 6 ]]; then
    adb shell input swipe 540 1600 540 500 350 || true
    sleep 6
  fi
done

adb logcat -d -v threadtime > runtime/evidence/logcat.txt || true
adb shell dumpsys activity processes > runtime/evidence/processes.txt || true
adb shell pidof com.zhiliaoapp.musically | tee runtime/evidence/tiktok-pid.txt || true

if [[ ! -s runtime/evidence/tiktok-pid.txt ]]; then
  echo "TikTok was not running at the end of the runtime probe" >&2
  exit 13
fi

echo "Direct TikTok runtime probe completed"
