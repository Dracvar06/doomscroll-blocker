# Contributing

The most valuable thing you can do here is **fix a broken rule**, and it needs
no Kotlin.

## When Doorman stops blocking something

Apps redesign themselves. When they do, the fingerprint Doorman uses to
recognise a screen stops matching, and it quietly stops blocking. It fails open
on purpose -- wrongly blocking a screen someone needs is far worse than missing
one -- but that means breakage is silent.

### If you are not a developer

Open Doorman, scroll to **Something not working?**, tap **Capture a report**,
switch to the app that is misbehaving, and wait five seconds. Then come back and
share the report in a GitHub issue.

The report contains the *structure* of the screen only. Every piece of text and
every content description is replaced with `<redacted>` before the file is
written, so a report captured on a conversation contains none of your messages.
The rules never match on words, so redaction costs nothing.

### If you have a cable and adb

```
./tools/enable-service.sh          # after every install; see below
./tools/dump-screen.sh feed        # capture whatever is on screen, labelled
./tools/compare-dumps.py dumps/**/*.json
```

`compare-dumps.py` prints the view ids unique to each screen. A good fingerprint
appears on the screen you want to block and on none of the screens you want left
alone. Put it in `app/src/main/assets/rules.json`, drop the dumps into
`app/src/test/resources/fixtures/`, and add a test.

## Traps worth knowing before you write a rule

- **A view being present does not mean its screen is showing.** Instagram keeps
  neighbouring tabs alive, so the DM inbox, the feed and the explore grid are all
  in the tree at once. Match on which tab is `selected`, never on what exists.
  Getting this wrong blocks people's messages.
- **Never match on visible text.** Content descriptions are translated: on a
  Catalan phone YouTube's Home tab reads "Inici". A rule built on English words
  works for English speakers and silently fails everyone else. Match on view ids
  and on position -- the selected tab's index survives translation.
- **List ids are shared between screens.** YouTube's `results` is the list id on
  the home feed, subscriptions, the library *and* search. A home rule built on it
  blocks all four.
- **Fixtures are captured on a Catalan device on purpose.** If a rule regresses
  to matching English text, the test suite fails.

## Rules of thumb

False positives are the failure that kills this kind of app. Blocking a screen
someone needs teaches them that blocks are things you push through, and once
that is learned every block is ignorable. When in doubt, allow.

Every screen must be individually switchable, and default to off unless it is
plainly an infinite feed.

## Building

```
./tools/dev-install.sh    # builds, installs, and re-enables the service
./gradlew test lintDebug
```

Installing or force-stopping the app makes Android drop its accessibility
service, and re-writing the setting with its existing value does nothing --
the system only re-reads on change. `tools/enable-service.sh` handles this, and
preserves any other accessibility service you have enabled.

Never use `adb shell am start -S` while testing: it force-stops the app, so the
service is dead before your code runs.

## Releases

`keystore.properties` (never committed) supplies the signing key:

```
storeFile=/absolute/path/to/doorman.jks
storePassword=...
keyAlias=doorman
keyPassword=...
```

Without it a release build still compiles, just unsigned.

## Licence

GPLv3. By contributing you agree your work is licensed the same way.
