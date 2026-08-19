# Doorman

An Android app that lets messages through, but not the feed.

YouTube, Instagram and TikTok bundle two different things into one app: the
parts you actually want — replying to someone, watching a reel a friend sent —
and an infinite feed you didn't ask for. Uninstalling kills both. Existing
blockers block the whole app, so answering a message means bypassing the block;
bypassing becomes routine, and then every block is ignorable. Most of them
charge a subscription for this.

Doorman blocks *screens*, not apps. The feed is blocked, the inbox is not.

Free, open source, and translated. No ads, no subscription, no telemetry.

## Status

**YouTube and Instagram both work.**

- YouTube: Shorts and the home feed are blocked. Watch page, search,
  subscriptions and library are left alone.
- Instagram: Reels, the home feed, and Explore/search are blocked. **Direct
  messages and your profile are left alone**, and the block never covers the tab
  bar, so your inbox is always one tap away.
- **A reel someone sends you plays.** Swipe to the next one and it stops. Go
  back to the conversation and open another reel they sent, and that one plays
  too.

Every screen is an individual switch in the app, with Strict / Balanced /
Nothing presets. To get into a blocked screen you open Doorman, wait out a
countdown that restarts if you walk away, and take a short timed pass.

Verified on a Pixel 10 (Android 16) against YouTube 21.32.4 and Instagram
442.0.0.46.79.

Not yet built: TikTok rules.

## Building

Requires Android Studio (bundled JDK is fine) and an Android device with USB
debugging on.

```
./tools/dev-install.sh
```

That builds a debug APK, installs it, and re-enables the accessibility service.

## How screens are identified

The accessibility framework exposes the screen as a tree of nodes -- the same
data a screen reader uses. Each carries a view id, a class name, a content
description, bounds and flags. Recognising a screen means finding a structural
fingerprint in that tree. YouTube's Shorts player has `reel_recycler`; nothing
else does.

Three traps, all found the hard way and all encoded in the tests:

- **Feed list ids are shared.** `results` is the list id on the home feed, on
  subscriptions, on the library AND on search results. A home-feed rule built on
  it blocks all four. The home feed is identified by which bottom-nav tab is
  selected instead.
- **A view being present does not mean its screen is showing.** Instagram's
  ViewPager keeps neighbouring tabs alive: while you sit on Reels, the DM inbox
  recycler, the feed list and the explore grid are all in the tree at once. Any
  rule built on presence blocks the inbox too. Instagram is matched on which
  tab carries `selected`, never on what exists.
- **Content descriptions are translated.** On a Catalan phone the Home tab is
  "Inici", not "Home". Any rule matching on visible words breaks in every other
  language. Rules match on view ids and on *position* -- the selected tab's
  index -- because a position survives translation. The test fixtures are
  captured from a Catalan device on purpose, so a rule that regresses to
  matching English text fails the suite.

### Watchable once, without becoming a feed

A reel a friend sends opens in the very same vertical pager the Reels tab uses,
so "which screen is this" cannot tell them apart on its own. Two things do: the
shared viewer carries `sender_username_or_fullname` and a reply bar -- the screen
itself says someone sent this -- and it has no bottom tab bar.

That screen gets the `ALLOW_ONCE` verdict: watchable, with a budget of one item.
Scroll events spend the budget when the pager reports a new index *or* when
accumulated vertical distance passes half a viewport, because index reporting is
unreliable and distance is the backstop. Replays, pauses, scrubbing and
horizontal swipes (which open a profile) deliberately spend nothing.

Backgrounding the app and returning does not refill the budget, or app-switching
would be a one-tap bypass. Arriving from a *different* screen does refill it,
because going back to the conversation and opening the next reel someone sent is
not doomscrolling.

### The block must never cover the tab bar

A blocked feed with a fully covered screen corners the user: Back often lands on
another blocked screen, so the only exit left is switching Doorman off -- the
exact habit this app exists to break. Each app's rules name the views that must
stay reachable (`keepVisibleViewIds`), and the overlay stops above them.

Making that work needs `FLAG_LAYOUT_IN_SCREEN`, `FLAG_LAYOUT_NO_LIMITS` and
`fitInsetsTypes = 0`. Without them the overlay window is inset below the status
bar while the measured tab-bar coordinate is absolute, and the mismatch is
exactly enough to swallow the tab bar.

Re-derive fingerprints after an app update with `./tools/dump-screen.sh <label>`
and `./tools/compare-dumps.py dumps/**/*.json`.

## Things that will waste your time otherwise

- **`flagReportViewIds` is mandatory** in `accessibility_service_config.xml`.
  Without it, `getViewIdResourceName()` returns null on every node and screen
  detection is blind.
- **Reinstalling the app disables its accessibility service**, and so does
  force-stopping it. `adb shell am start -S` force-stops, so never use `-S` when
  testing — the service will be dead before your code runs.
- **Re-writing `enabled_accessibility_services` with its current value does
  nothing.** The system only re-reads that setting when it changes, so the entry
  must be removed and re-added. `tools/enable-service.sh` does this.
- **That setting is one colon-separated list shared by every app.** Overwriting
  it rather than appending will silently switch off the user's password manager
  or screen reader.
- **Don't check whether the service is on by parsing that setting.** After a
  reinstall the string can still name a service the system has already dropped.
  Ask `AccessibilityManager.getEnabledAccessibilityServiceList()` instead — it
  answers from the registry that actually decides whether events arrive.

## Licence

GPLv3.
