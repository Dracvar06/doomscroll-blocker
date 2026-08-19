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

- YouTube: the Shorts feed and the home feed are blocked. Watch page, search,
  subscriptions and library are left alone. A Short opened on its own -- from a
  link someone sent you -- plays, and the swipe to the next one is blocked.
- Instagram: Reels, the home feed, and the Explore grid are blocked. **Direct
  messages, your profile, and searching for people are left alone.** The block
  never covers the tab bar or the search bar, so your inbox is one tap away and
  you can still look somebody up.
- **Any single reel you open plays, and stops there.** From a conversation, from
  a post in the feed, from someone's story, from a link -- it plays, and the
  swipe to the next one is blocked. Go back and open another reel deliberately
  and that one plays too.

Every screen is an individual switch in the app, with Strict / Balanced /
Nothing presets. Switching a block **off** waits a configurable delay (30 s by
default) and is cancelled if you leave the app; switching one **on** is
immediate, because friction belongs on the decision you would regret. To get into a blocked screen you open Doorman, wait out a
countdown that restarts if you walk away, and take a short timed pass.

Verified on a Pixel 10 (Android 16) against YouTube 21.32.4 and Instagram
442.0.0.46.79.

Not yet built: TikTok rules.

## Installing it

There is no store listing yet. Grab the APK from Releases, allow your browser to
install unknown apps when Android asks, then open Doorman and follow the prompt
to grant accessibility access.

Doorman asks for one permission and nothing else. It has no network permission
at all, so nothing it reads can leave the phone even in principle. No ads, no
subscription, no accounts, no analytics.

### When it stops working

Apps redesign themselves and fingerprints break. Doorman fails open -- it stops
blocking rather than blocking the wrong thing -- so the symptom is silence.

Open Doorman, scroll to **Something not working?**, tap **Capture a report**,
switch to the app that is misbehaving, wait five seconds, and share the report
in an issue. Reports contain the screen's structure only: all text is replaced
with `<redacted>` before the file is written, so a report captured on a
conversation contains none of your messages.

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

A reel opens in the very same vertical pager the Reels tab uses, so "which
screen is this" cannot tell them apart on its own. The tab bar does: the Reels
tab keeps it, a standalone reel viewer has none.

An earlier version matched the sender views a DM-shared reel carries, which was
too narrow by half. It recognised reels arriving from a conversation and left
every other route wide open -- open a reel from a post in the feed or from
someone's story and you had an unlimited feed again. The rule is now "any reel
viewer that is not the Reels tab", because reaching one always means a single
reel was opened deliberately.

That screen gets the `ALLOW_ONCE` verdict: watchable, with a budget of one item.
Scroll events spend the budget when the pager reports a new index *or* when
accumulated vertical distance passes half a viewport, because index reporting is
unreliable and distance is the backstop. Replays, pauses, scrubbing and
horizontal swipes (which open a profile) deliberately spend nothing.

Backgrounding the app and returning does not refill the budget, or app-switching
would be a one-tap bypass. Arriving from a *different* screen does refill it,
because going back to the conversation and opening the next reel someone sent is
not doomscrolling.

### The block must never corner the user

A fully covered screen leaves no way out but switching Doorman off -- the exact
habit this app exists to break. Two controls have to survive every block:

- **the tab bar**, or the way to your messages is gone and Back often lands on
  another blocked screen;
- **the search bar**, because Instagram puts search and the Explore grid behind
  one tab. Blocking that tab to keep the grid away also took away looking
  someone up, which has nothing to do with doomscrolling.

So the overlay is a band, not a curtain: it starts below `keepVisibleTopViewIds`
and ends above `keepVisibleViewIds`. On Instagram's Explore tab that works out
as `Rect(0, 301 - 1080, 2235)` -- grid covered, search bar and tabs live.

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Fixing a broken rule is a JSON edit and
needs no Kotlin.

Translations are welcome. English and Catalan ship today; every user-facing
string lives in `res/values/strings.xml`, and nothing in the blocking rules
depends on language.

## Licence

GPLv3. Free as in freedom, and free as in the thing every comparable app
charges a subscription for.
