#!/usr/bin/env bash
# Check, on whatever phone is plugged in, that a blocked app stays hidden in the
# moments around a block: while a swipe home is held, in recents, while it opens
# again, and when a tab into a blocked screen is tapped. Written for borrowing a
# Samsung for ten minutes before a release; it runs on any phone.
#
#   ./tools/check-covers.sh
#
# Needs: a debug build of Doorman with its service on (tools/dev-install.sh),
# Instagram signed in with its Home (feed) blocked in Doorman -- the default --
# the phone unlocked, and Doorman's "when a block holds you" set to Nowhere, or
# it may send the phone elsewhere mid-check. Nothing is posted, liked or opened
# beyond Instagram's own tabs, no screenshot is taken of the messages, and the
# only dumps are Doorman's redacted ones, deleted from the phone at once.
#
# Writes screenshots and Doorman's log to ./cover-check-<model>/ for a look.
# No pipefail: `grep -q` and `head` stop reading early, which it counts as failure.
set -eu
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
IG=com.instagram.android
DUMPS=/sdcard/Android/data/cat.doorman.app/files/dumps

say() { printf '%s\n' "$*"; }
die() { say "error: $*" >&2; exit 2; }
sh() { "$ADB" shell "$@" | tr -d '\r'; }

# ---- the phone is ready ------------------------------------------------------
"$ADB" get-state >/dev/null 2>&1 || die "no phone connected (adb devices)"
sh input keyevent KEYCODE_WAKEUP >/dev/null || true
sh dumpsys window | grep "mDreamingLockscreen=true" >/dev/null && die "the phone is locked; unlock it and run this again"
# A phone left on a cable may be showing its screen saver, which sits over
# everything without being the lock screen.
sh dumpsys window | grep "mCurrentFocus=.*DreamActivity" >/dev/null && die "the phone is showing its screen saver; tap it, unlock, and run this again"
sh pm path "$IG" >/dev/null 2>&1 || die "Instagram is not installed"
sh dumpsys accessibility | grep "cat.doorman.app/" >/dev/null || die "Doorman's service is not on (tools/enable-service.sh)"

MODEL="$(sh getprop ro.product.manufacturer) $(sh getprop ro.product.model)"
LAUNCHER="$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | cut -d/ -f1)"
NAV="$(sh settings get secure navigation_mode)"
read -r W H < <(sh wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
OUT="cover-check-$(printf '%s' "$MODEL" | tr -c 'A-Za-z0-9' '-')"
mkdir -p "$OUT"
say "phone:    $MODEL (Android API $(sh getprop ro.build.version.sdk)), screen ${W}x${H}"
say "launcher: $LAUNCHER"
say "nav:      $([ "$NAV" = 2 ] && echo gestures || echo "buttons ($NAV): the held-swipe check needs gesture navigation and is skipped")"
say "output:   $OUT/"

restore() {
  sh svc power stayon false >/dev/null 2>&1 || true
  sh input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  sh rm -f /sdcard/cover-check-*.png >/dev/null 2>&1 || true
}
trap restore EXIT
sh svc power stayon usb >/dev/null 2>&1 || true
"$ADB" logcat -G 16M >/dev/null 2>&1 || true

log() { "$ADB" logcat -b all -d -s 'Doorman:*' | tr -d '\r'; }
shot() { sh screencap -p "/sdcard/cover-check-$1.png"; "$ADB" pull "/sdcard/cover-check-$1.png" "$OUT/$1.png" >/dev/null 2>&1; sh rm -f "/sdcard/cover-check-$1.png"; }
RESULTS=()
result() { RESULTS+=("$(printf '%-34s %s' "$1" "$2")"); say "  -> $2"; }

open_blocked() {
  # From the home screen, so the block is drawn afresh and shows in the log.
  sh input keyevent KEYCODE_HOME; sleep 1.5
  "$ADB" logcat -c
  sh monkey -p "$IG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  log | grep "overlay shown for ig_" >/dev/null || die "no block came up on Instagram; is its Home blocked in Doorman, and Instagram on Home?"
}

# A swipe up from the bottom edge, held at 60% of the height for $1 seconds.
hold_swipe() {
  local x=$((W / 2)) y0=$((H - 12)) y=$((H * 60 / 100)) secs=$1 tail=$2
  sh "input motionevent DOWN $x $y0; for f in 1 2 3 4 5 6; do input motionevent MOVE $x \$(( $y0 - ($y0 - $y) * f / 6 )); sleep 0.05; done; sleep $secs; $tail"
}

# ---- 1. held swipe -----------------------------------------------------------
say ""; say "1. A swipe home held for 3 s over the blocked Home"
if [ "$NAV" = 2 ]; then
  open_blocked
  "$ADB" logcat -c
  hold_swipe 3 "screencap -p /sdcard/cover-check-1-held.png; for f in 1 2 3; do input motionevent MOVE $((W / 2)) \$(( $H * (60 + 13 * f) / 100 )); sleep 0.05; done; input motionevent UP $((W / 2)) $((H - 12))"
  sleep 2
  "$ADB" pull /sdcard/cover-check-1-held.png "$OUT/1-held.png" >/dev/null 2>&1; sh rm -f /sdcard/cover-check-1-held.png
  L="$(log)"
  printf '%s\n' "$L" | grep "windows\[" | grep -o "pkg=$IG[^|]*b=[0-9 -]*" | sed 's/.*b=/    instagram window at: /' | sort -u | head -5
  if printf '%s\n' "$L" | grep "card cover shown" | grep -v "at 0 0 $W $H" >/dev/null; then
    result "held swipe" "PASS: the card is reported where it is drawn and covered"
  elif printf '%s\n' "$L" | grep "card cover shown" >/dev/null; then
    result "held swipe" "PARTLY: only covered at full size (look at 1-held.png)"
  else
    result "held swipe" "FALLBACK: no card reported; the block drops after 1.2 s as before"
  fi
  printf '%s\n' "$L" | grep "overlay shown for ig_" >/dev/null && say "  back in the app, the block is back" || say "  (did not get back into Instagram)"
else
  result "held swipe" "SKIPPED: button navigation"
fi

# ---- 2. recents ----------------------------------------------------------------
say ""; say "2. Recents after leaving the blocked Home"
open_blocked
sh input keyevent KEYCODE_HOME; sleep 2
"$ADB" logcat -c
sh input keyevent KEYCODE_APP_SWITCH; sleep 2.5
shot 2-recents
L="$(log)"
if printf '%s\n' "$L" | grep "recents cover shown" >/dev/null; then
  result "recents card" "PASS: Instagram's card is covered"
else
  # Whether the launcher labels its cards at all: counted from a redacted dump,
  # which says a label is there without saying what it is.
  sh am broadcast -a cat.doorman.app.DUMP -p cat.doorman.app --es label cover-check --ez redact true >/dev/null; sleep 2
  F="$(sh ls -t "$DUMPS" | sed -n 1p)"; TMP="$(mktemp)"
  "$ADB" pull "$DUMPS/$F" "$TMP" >/dev/null 2>&1 || true; sh rm -f "$DUMPS/$F"
  TALL="$(python3 -I - "$TMP" "$H" <<'PY'
import json, sys
try: nodes = json.load(open(sys.argv[1])).get("nodes", [])
except Exception: nodes = []
h = int(sys.argv[2]); n = 0
for x in nodes:
    try: l, t, r, b = (int(v) for v in (x.get("bounds") or "").split(","))
    except ValueError: continue
    if x.get("contentDescription") and x.get("visible") and (b - t) * 100 >= h * 30: n += 1
print(n)
PY
)"; rm -f "$TMP"
  if [ "$TALL" -gt 0 ]; then
    result "recents card" "FAIL: $TALL labelled card(s) but none taken for Instagram (look at 2-recents.png)"
  else
    result "recents card" "FALLBACK: this launcher does not label its cards; the card shows as before"
  fi
fi
sh input keyevent KEYCODE_HOME; sleep 2

# ---- 3. opening it again ---------------------------------------------------------
say ""; say "3. Opening Instagram again from the home screen"
"$ADB" logcat -c
sh "monkey -p $IG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 0.15; screencap -p /sdcard/cover-check-3-opening.png"
sleep 2
"$ADB" pull /sdcard/cover-check-3-opening.png "$OUT/3-opening.png" >/dev/null 2>&1; sh rm -f /sdcard/cover-check-3-opening.png
L="$(log)"
FIRST="$(printf '%s\n' "$L" | grep -oE "card cover shown at 0 0|overlay shown for" | head -1)"
case "$FIRST" in
  "card cover shown at 0 0") result "opening again" "PASS: covered from the first moment until the block" ;;
  "overlay shown for") result "opening again" "NO COVER: the block came straight up (look at 3-opening.png for a glimpse)" ;;
  *) result "opening again" "FAIL: no block at all" ;;
esac

# ---- 4. a tab into the blocked Home ----------------------------------------------
say ""; say "4. Messages tab, then the Home tab"
tab() {
  sh am broadcast -a cat.doorman.app.DUMP -p cat.doorman.app --es label cover-check --ez redact true >/dev/null; sleep 2
  local f tmp; f="$(sh ls -t "$DUMPS" | sed -n 1p)"; tmp="$(mktemp)"
  "$ADB" pull "$DUMPS/$f" "$tmp" >/dev/null 2>&1 || true; sh rm -f "$DUMPS/$f"
  python3 -I - "$tmp" "$1" <<'PY'
import json, sys
try: nodes = json.load(open(sys.argv[1])).get("nodes", [])
except Exception: nodes = []
for n in nodes:
    if (n.get("viewId") or "").endswith("/" + sys.argv[2]) and n.get("visible"):
        l, t, r, b = (int(x) for x in n["bounds"].split(","))
        print((l + r) // 2, (t + b) // 2); break
PY
  rm -f "$tmp"
}
DIRECT="$(tab direct_tab)"; FEED="$(tab feed_tab)"
if [ -n "$DIRECT" ] && [ -n "$FEED" ] && sh dumpsys window | grep "mCurrentFocus=.*$IG" >/dev/null; then
  sh input tap $DIRECT; sleep 2.5
  "$ADB" logcat -c
  sh input tap $FEED; sleep 2
  if log | grep "tapped into ig_feed" >/dev/null; then
    result "tab into a blocked screen" "PASS: covered at the tap"
  else
    result "tab into a blocked screen" "FAIL: no cover at the tap"
  fi
else
  result "tab into a blocked screen" "SKIPPED: could not find Instagram's tab bar"
fi

log > "$OUT/doorman.log"
say ""; say "== $MODEL"
printf '%s\n' "${RESULTS[@]}"
say "screenshots and log in $OUT/ (only screens of the blocked Home, recents and the home screen)"
