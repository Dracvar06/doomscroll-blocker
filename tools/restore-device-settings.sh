#!/usr/bin/env bash
# Puts back the display settings that were changed to make testing bearable.
# The originals were read from the device before anything was touched.
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
"$ADB" shell settings put system screen_off_timeout 60000
"$ADB" shell settings put secure screensaver_enabled 1
echo -n "screen_off_timeout: "; "$ADB" shell settings get system screen_off_timeout
echo -n "screensaver_enabled: "; "$ADB" shell settings get secure screensaver_enabled
