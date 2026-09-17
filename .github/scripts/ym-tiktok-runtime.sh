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

write_auto_pref() {
  local state="$1"
  cat > runtime/ym_auto_comment.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enabled" value="$state" />
    <int name="min_interval_seconds" value="3" />
</map>
XML
  adb push runtime/ym_auto_comment.xml /data/local/tmp/ym_auto_comment.xml >/dev/null
  adb shell run-as com.ym.lite.stable mkdir -p shared_prefs
  adb shell run-as com.ym.lite.stable cp /data/local/tmp/ym_auto_comment.xml shared_prefs/ym_auto_comment.xml
}

capture_stage() {
  local name="$1"
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "runtime/evidence/ui-${name}.xml" >/dev/null 2>&1 || true
  adb exec-out screencap -p > "runtime/evidence/screen-${name}.png" || true
  adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' > "runtime/evidence/activity-${name}.txt" || true
  adb shell dumpsys accessibility > "runtime/evidence/accessibility-${name}.txt" || true
  adb shell 'run-as com.ym.lite.stable cat shared_prefs/ym_local.xml' > "runtime/evidence/ym-local-${name}.xml" 2>/dev/null || true
}

tap_safe_label() {
  local label="$1"
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml runtime/current-ui.xml >/dev/null 2>&1 || return 1
  local coords
  coords="$(python3 - runtime/current-ui.xml "$label" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, wanted = sys.argv[1], sys.argv[2].strip().casefold()
try:
    root = ET.parse(path).getroot()
except Exception:
    raise SystemExit(1)
for node in root.iter('node'):
    values = [node.attrib.get('text',''), node.attrib.get('content-desc','')]
    if any(v.strip().casefold() == wanted for v in values if v):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            raise SystemExit(0)
raise SystemExit(1)
PY
  )" || return 1
  local x y
  read -r x y <<<"$coords"
  echo "safe tap: $label @ $x,$y" | tee -a runtime/evidence/navigation.txt
  adb shell input tap "$x" "$y"
  sleep 2
  return 0
}


set_test_adult_birthdate() {
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml runtime/current-ui.xml >/dev/null 2>&1 || return 1
  if ! grep -Eiq "birthdate|Year picker" runtime/current-ui.xml; then
    return 1
  fi

  echo "setting emulator-only adult birthdate" | tee -a runtime/evidence/navigation.txt
  # TikTok exposes the year wheel as a scrollable SeekBar at x ~= 802.
  # Move the wheel one row toward older years per short downward swipe.
  for _ in $(seq 1 30); do
    adb shell input swipe 802 1420 802 1540 90
  done
  sleep 2
  capture_stage birthdate-adjusted

  if tap_safe_label "Continue"; then
    sleep 6
    capture_stage birthdate-continued
    return 0
  fi
  echo "birthdate Continue was still unavailable" | tee -a runtime/evidence/navigation.txt
  return 1
}

is_feed_visible() {
  adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' > runtime/current-activity.txt || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml runtime/current-ui.xml >/dev/null 2>&1 || true
  if grep -q 'com.ss.android.ugc.aweme.main.MainActivity' runtime/current-activity.txt && \
     grep -Eiq 'For You|Following|Home|Friends|comment|comments' runtime/current-ui.xml; then
    return 0
  fi
  return 1
}

# Keep auto-comment OFF while TikTok is in first-run/login/onboarding screens.
write_auto_pref false
adb shell run-as com.ym.lite.stable cat shared_prefs/ym_auto_comment.xml > runtime/evidence/ym-auto-comment-initial.xml
adb shell appops set com.ym.lite.stable SYSTEM_ALERT_WINDOW allow || true
adb shell settings put secure enabled_accessibility_services com.ym.lite.stable/com.ym.lite.automation.YmTikTokAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am start-foreground-service -n com.ym.lite.stable/com.ym.lite.overlay.YmOverlayService || true

# Start TikTok's real main activity directly. It is allowed to redirect to onboarding; we record that.
adb shell am start -W -n com.zhiliaoapp.musically/com.ss.android.ugc.aweme.main.MainActivity \
  > runtime/evidence/main-activity-launch.txt 2>&1 || true
sleep 12
capture_stage pre-nav

# Dismiss only non-account system/onboarding controls. Never choose a login provider or enter credentials.
for round in 1 2 3 4 5 6 7 8; do
  is_feed_visible && break

  # TikTok guest mode requires an age gate even without account sign-in.
  if set_test_adult_birthdate; then
    continue
  fi

  tapped=false
  for label in "Got it" "Skip" "Agree and continue" "Don’t allow" "Don't allow" "Not now" "Maybe later" "Continue as guest" "Close" "Later"; do
    if tap_safe_label "$label"; then
      tapped=true
      capture_stage "nav-${round}-${label// /_}"
      break
    fi
  done
  if [[ "$tapped" == false ]]; then
    # One controlled Back from TikTok's signup/onboarding screen may return to guest/main feed.
    if grep -Eq 'I18nSignUpActivity|NewUserJourneyActivity' runtime/current-activity.txt 2>/dev/null; then
      echo "controlled BACK from onboarding round $round" | tee -a runtime/evidence/navigation.txt
      adb shell input keyevent KEYCODE_BACK
      sleep 3
      capture_stage "nav-${round}-back"
    else
      break
    fi
  fi
done

if ! is_feed_visible; then
  echo "false" > runtime/evidence/feed-reached.txt
  capture_stage feed-not-reached
  adb logcat -d -v threadtime > runtime/evidence/logcat.txt || true
  adb shell pidof com.zhiliaoapp.musically | tee runtime/evidence/tiktok-pid.txt || true
  echo "TikTok installed and launched, but an unauthenticated feed was not reachable without account interaction."
  exit 0
fi

echo "true" > runtime/evidence/feed-reached.txt
capture_stage feed-ready

# Only after a real feed is visible do we enable button 4 and reconnect Accessibility so prefs reload cleanly.
adb shell settings put secure enabled_accessibility_services '' || true
adb shell settings put secure accessibility_enabled 0 || true
write_auto_pref true
adb shell run-as com.ym.lite.stable cat shared_prefs/ym_auto_comment.xml > runtime/evidence/ym-auto-comment-enabled.xml
adb shell settings put secure enabled_accessibility_services com.ym.lite.stable/com.ym.lite.automation.YmTikTokAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 4

for i in 1 2 3 4 5 6; do
  echo "=== feed cycle $i ==="
  capture_stage "$i"
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

echo "Direct TikTok runtime feed probe completed"
