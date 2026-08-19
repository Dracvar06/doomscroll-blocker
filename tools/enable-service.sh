#!/usr/bin/env bash
# Enable Doorman's accessibility service on the connected device.
#
# The service gets dropped by the system whenever the app is reinstalled OR
# force-stopped (including `am start -S`, so never use that flag when testing).
# Re-writing the setting with its existing value is a no-op, because the system
# only re-reads on change -- the entry must be removed and re-added.
#
# Every other enabled service is preserved: this is one colon-separated list
# shared by all apps, and clobbering it would silently disable a password
# manager or screen reader.
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERVICE="cat.doorman.app/cat.doorman.app.service.DoormanAccessibilityService"

CURRENT=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$CURRENT" = "null" ] && CURRENT=""
OTHERS=$(printf '%s' "$CURRENT" | tr ':' '\n' | grep -vxF "$SERVICE" | grep -v '^$' | paste -sd: - || true)

"$ADB" shell settings put secure enabled_accessibility_services "$OTHERS"
sleep 1
"$ADB" shell settings put secure enabled_accessibility_services "${OTHERS:+$OTHERS:}$SERVICE"
"$ADB" shell settings put secure accessibility_enabled 1
sleep 2

if "$ADB" shell dumpsys accessibility | grep -q "label=Doorman"; then
  echo "bound: yes"
else
  echo "bound: NO -- enabled in settings but not running" >&2
  exit 1
fi
