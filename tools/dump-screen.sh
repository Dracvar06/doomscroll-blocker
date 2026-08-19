#!/usr/bin/env bash
# Capture the accessibility tree of whatever app is on screen right now.
#
#   ./tools/dump-screen.sh shorts     # label the dump "shorts"
#
# The device must be awake and UNLOCKED. While it dreams or sits on the
# lockscreen the only windows that exist belong to systemui, and the dump is 50
# nodes of status bar rather than the app you meant to capture.
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
LABEL="${1:-}"

"$ADB" shell svc power stayon usb >/dev/null 2>&1 || true
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

if "$ADB" shell dumpsys window | grep -q "mDreamingLockscreen=true"; then
  echo "device is locked -- unlock it and run this again" >&2
  exit 1
fi

"$ADB" logcat -c
"$ADB" shell am broadcast -a cat.doorman.app.DUMP -p cat.doorman.app ${LABEL:+--es label "$LABEL"} >/dev/null
sleep 2
"$ADB" logcat -d -s "Doorman:V" | grep -E "^.*(dump:|viewId:)" || {
  echo "no dump produced -- is the service bound? (tools/enable-service.sh)" >&2
  exit 1
}

mkdir -p dumps
"$ADB" pull /sdcard/Android/data/cat.doorman.app/files/dumps/ dumps/ >/dev/null 2>&1 || true
echo "pulled to ./dumps/"
