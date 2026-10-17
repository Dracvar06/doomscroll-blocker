#!/usr/bin/env bash
# Check that Instagram's comments cannot be used to watch the blocked Reels tab,
# on whatever phone is plugged in.
#
#   ./tools/check-comments-origin.sh          # 5 rounds per route
#   ./tools/check-comments-origin.sh 10       # 10 rounds per route
#
# The trick it replays: on the blocked Reels tab, leave the app, come straight
# back, and tap the comments before the block is drawn again. Tapping the
# comments hides Instagram's tab bar for a frame, and a reel without a tab bar is
# what a reel someone sent you looks like, so the comments used to stay open
# with the Reels tab playing above them. It is tried two ways: through the home
# screen, and through the recent-apps screen, because phones disagree about
# which app draws recents and Doorman treats "came back from the launcher" and
# "came from another app" differently.
#
# Needs: a debug build of Doorman with its service on (tools/dev-install.sh),
# Instagram signed in, the phone unlocked, and Reels switched on in Doorman.
# Nothing is posted or liked: the script only opens the Reels tab and the
# comments sheet, and reads positions from Doorman's own dumps with all text
# redacted, which it deletes from the phone straight away. The block is not
# drawn while it runs (debug NOBLOCK), so the media volume is set to 0 and put
# back afterwards.
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROUNDS="${1:-5}"
IG=com.instagram.android
DUMPS=/sdcard/Android/data/cat.doorman.app/files/dumps

say() { printf '%s\n' "$*"; }
die() { say "error: $*" >&2; exit 2; }

# ---- the phone is ready ------------------------------------------------------
"$ADB" get-state >/dev/null 2>&1 || die "no phone connected (adb devices)"
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell dumpsys window | grep -q "mDreamingLockscreen=true" && die "the phone is locked; unlock it and run this again"
"$ADB" shell pm path "$IG" >/dev/null 2>&1 || die "Instagram is not installed"
"$ADB" shell dumpsys accessibility | grep -q "cat.doorman.app/" || die "Doorman's service is not on (tools/enable-service.sh)"

MODEL="$("$ADB" shell getprop ro.product.manufacturer | tr -d '\r') $("$ADB" shell getprop ro.product.model | tr -d '\r')"
SDK="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
HOMES="$("$ADB" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | tr -d '\r' | sed -n 's#^ *\([A-Za-z0-9_.]*\)/.*#\1#p' | sort -u | tr '\n' ' ')"
say "phone:     $MODEL (Android API $SDK)"
say "launchers: $HOMES"

# ---- put things back however it ends -----------------------------------------
VOLUME="$("$ADB" shell cmd media_session volume --stream 3 --get 2>/dev/null | tr -d '\r' | sed -n 's/.*volume is \([0-9]*\).*/\1/p')"
restore() {
  "$ADB" shell svc power stayon false >/dev/null 2>&1 || true
  "$ADB" shell am broadcast -a cat.doorman.app.NOBLOCK -p cat.doorman.app --ez on false >/dev/null 2>&1 || true
  [ -n "$VOLUME" ] && "$ADB" shell cmd media_session volume --stream 3 --set "$VOLUME" >/dev/null 2>&1 || true
  "$ADB" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
}
trap restore EXIT
# A run takes a few minutes; a screen that turns off locks the phone halfway.
"$ADB" shell svc power stayon usb >/dev/null 2>&1 || true
"$ADB" shell cmd media_session volume --stream 3 --set 0 >/dev/null 2>&1 || true
"$ADB" shell am broadcast -a cat.doorman.app.NOBLOCK -p cat.doorman.app --ez on true >/dev/null

# Centre of the first visible node whose view id ends in $1, from a redacted
# dump of the screen as it is now. Empty if there is none.
centre_of() {
  "$ADB" shell am broadcast -a cat.doorman.app.DUMP -p cat.doorman.app --es label origin-check --ez redact true >/dev/null
  sleep 2
  local f; f="$("$ADB" shell ls -t "$DUMPS" | tr -d '\r' | head -1)"
  local tmp; tmp="$(mktemp)"
  "$ADB" pull "$DUMPS/$f" "$tmp" >/dev/null 2>&1 || true
  "$ADB" shell rm -f "$DUMPS/$f"
  python3 -I - "$tmp" "$1" <<'PY'
import json, sys
try:
    nodes = json.load(open(sys.argv[1])).get("nodes", [])
except Exception:
    nodes = []
for n in nodes:
    v = n.get("viewId") or ""
    b = n.get("bounds") or ""
    if v.endswith("/" + sys.argv[2]) and n.get("visible"):
        l, t, r, bt = (int(x) for x in b.split(","))
        if r > l and bt > t:
            print((l + r) // 2, (t + bt) // 2)
            break
PY
  rm -f "$tmp"
}

# Where to tap for the comments on the reel showing now, or empty if that cannot
# be done safely. A tap that lands on the like button would like the reel on the
# owner's account, so the position is read fresh from this very reel every time
# (each reel lays its buttons out differently), and refused if it is anywhere
# near the like button or not in the right-hand column where the buttons live.
comments_tap() {
  "$ADB" shell am broadcast -a cat.doorman.app.DUMP -p cat.doorman.app --es label origin-check --ez redact true >/dev/null
  sleep 2
  local f; f="$("$ADB" shell ls -t "$DUMPS" | tr -d '\r' | head -1)"
  local tmp; tmp="$(mktemp)"
  "$ADB" pull "$DUMPS/$f" "$tmp" >/dev/null 2>&1 || true
  "$ADB" shell rm -f "$DUMPS/$f"
  python3 -I - "$tmp" "$WIDTH" <<'PY'
import json, sys
try:
    nodes = json.load(open(sys.argv[1])).get("nodes", [])
except Exception:
    nodes = []
width = int(sys.argv[2])
def box(name):
    for n in nodes:
        if (n.get("viewId") or "").endswith("/" + name) and n.get("visible"):
            l, t, r, b = (int(x) for x in n["bounds"].split(","))
            if r > l and b > t:
                return l, t, r, b
comment, like = box("comment_button"), box("like_button")
if comment and like:
    x, y = (comment[0] + comment[2]) // 2, (comment[1] + comment[3]) // 2
    clear_of_like = y > like[3] + 20 or y < like[1] - 20
    if clear_of_like and x > width * 0.7:
        print(x, y)
PY
  rm -f "$tmp"
}

last_verdict() { "$ADB" logcat -d -s Doorman:I | tr -d '\r' | grep "evaluate: pkg=$IG" | tail -1; }

# ---- get to the Reels tab ----------------------------------------------------
to_reels_tab() {
  # Instagram reopens wherever it was left, and a post or a conversation has no
  # tab bar: step back until there is one (Back past the first screen leaves the
  # app, and the next try opens it again).
  local tab="" try
  for try in 1 2 3 4 5; do
    "$ADB" shell monkey -p "$IG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 3
    tab="$(centre_of clips_tab)"
    [ -n "$tab" ] && break
    "$ADB" shell input keyevent KEYCODE_BACK
    sleep 1
  done
  [ -n "$tab" ] || die "cannot see Instagram's tab bar; open Instagram on its home feed and run this again"
  "$ADB" logcat -c
  "$ADB" shell input tap $tab
  sleep 3
  last_verdict | grep -q "screen=ig_reels" || die "the Reels tab was not recognised as blocked (is Reels switched on in Doorman?)"
}

WIDTH="$("$ADB" shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1/p' | tail -1)"
HEIGHT="$("$ADB" shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\2/p' | tail -1)"
to_reels_tab
say ""

PASS=0; FAIL=0; SKIP=0
RECENTS_OWNER=""

# One round: leave by $1 (home | recents), come back, tap the comments at once.
round() {
  local route=$1 n=$2
  local COMMENTS; COMMENTS="$(comments_tap)"
  if [ -z "$COMMENTS" ]; then
    say "$route #$n: SKIP (no comment button it is safe to tap on this reel)"; SKIP=$((SKIP + 1))
    return
  fi
  "$ADB" logcat -c
  if [ "$route" = home ]; then
    "$ADB" shell "input keyevent KEYCODE_HOME; sleep 2; monkey -p $IG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 0.25; if dumpsys window 2>/dev/null | grep -q 'mCurrentFocus=.*$IG'; then input tap $COMMENTS; fi" || true
  else
    # The app just left is the card in the middle of recents on every launcher
    # seen so far. Never tap the comments' position in some other app: check
    # Instagram is back in front first.
    local cx=$(( WIDTH / 2 )) cy=$(( HEIGHT / 2 ))
    "$ADB" shell "input keyevent KEYCODE_APP_SWITCH; sleep 1.5; input tap $cx $cy; sleep 0.5; if dumpsys window 2>/dev/null | grep -q 'mCurrentFocus=.*$IG'; then input tap $COMMENTS; fi" || true
  fi
  sleep 4
  local log; log="$("$ADB" logcat -d -s Doorman:I | tr -d '\r')"
  if [ "$route" = recents ] && [ -z "$RECENTS_OWNER" ]; then
    RECENTS_OWNER="$(printf '%s\n' "$log" | sed -n "s/.*foreground: $IG -> \([^ ]*\).*/\1/p" | head -1)"
  fi
  local evals; evals="$(printf '%s\n' "$log" | grep "evaluate: pkg=$IG" | sed -E 's/ enabled=\[[^]]*\]//')"
  local last; last="$(printf '%s\n' "$evals" | tail -1)"
  local result
  if [ -z "$last" ] || ! printf '%s\n' "$log" | grep -q "foreground: [^ ]* -> $IG"; then
    result="SKIP (did not get back into Instagram)"; SKIP=$((SKIP + 1))
  elif ! printf '%s\n' "$evals" | grep -qE "kept from before|screen=null|screen=ig_single_reel"; then
    # Nothing but the Reels tab: the comments never opened, so this proves nothing.
    result="SKIP (the comments did not open)"; SKIP=$((SKIP + 1))
  elif printf '%s\n' "$evals" | grep -q "outcome=ALLOW_ONCE screen=ig_single_reel" ||
       ! printf '%s\n' "$last" | grep -q "outcome=BLOCK screen=ig_reels"; then
    result="FAIL"; FAIL=$((FAIL + 1))
  else
    result="pass"; PASS=$((PASS + 1))
  fi
  say "$route #$n: $result"
  [ "$result" = FAIL ] && printf '%s\n' "$evals" | sed 's/^.*evaluate: /    /'
  # Close the sheet and make sure we are on the Reels tab again.
  "$ADB" shell input keyevent KEYCODE_BACK
  sleep 2
  last_verdict | grep -q "screen=ig_reels" || to_reels_tab
}

for i in $(seq 1 "$ROUNDS"); do round home "$i"; done
for i in $(seq 1 "$ROUNDS"); do round recents "$i"; done
[ $((PASS + FAIL)) -gt 0 ] || { say "no round got as far as the comments"; exit 2; }

say ""
say "recents is drawn by: ${RECENTS_OWNER:-unknown}"
case " $HOMES " in
  *" $RECENTS_OWNER "*) say "  -> a launcher: coming back through recents counts as coming back, as on a Pixel" ;;
  *) [ -n "$RECENTS_OWNER" ] && say "  -> NOT a launcher: coming back through recents counts as arriving from another app" ;;
esac
say "passed $PASS, failed $FAIL, skipped $SKIP"
[ "$FAIL" -eq 0 ]
