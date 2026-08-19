#!/usr/bin/env bash
# Build, install, and actually re-enable Doorman's accessibility service.
#
# Two things make this less obvious than it looks:
#
#  1. Installing a new APK makes Android drop the app's accessibility services.
#  2. Writing the same value back into ENABLED_ACCESSIBILITY_SERVICES does
#     nothing, because the system only re-reads that setting when it *changes*.
#     The entry has to be removed and then re-added.
#
# Other enabled services (a password manager, a screen reader) are preserved:
# the setting is one colon-separated list shared by every app, and clobbering it
# would silently switch those off.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERVICE="cat.doorman.app/cat.doorman.app.service.DoormanAccessibilityService"

cd "$(dirname "$0")/.."
./gradlew assembleDebug -q
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
echo "installed"

exec "$(dirname "$0")/enable-service.sh"
