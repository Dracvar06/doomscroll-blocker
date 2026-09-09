# Releasing Doorman

What to do every time a new version goes out. Written so that the person doing
it in six months has nothing to remember.

## Before the first release (once)

1. **Create the signing key.** Never commit it; `.gitignore` already excludes
   `*.jks`, `*.keystore` and `keystore.properties`. Keep a copy of the key and
   its passwords somewhere that is not this laptop. **Losing the key means every
   future update is a different app to Android** — users would have to
   uninstall to upgrade, and lose their settings.

   ```bash
   keytool -genkeypair -v -keystore doorman-release.jks -alias doorman -keyalg RSA -keysize 4096 -validity 10000
   ```

   Then create `keystore.properties` in the repo root:

   ```
   storeFile=/absolute/path/to/doorman-release.jks
   storePassword=…
   keyAlias=doorman
   keyPassword=…
   ```

2. **Publish the privacy policy.** `PRIVACY.md` must be reachable at a public
   URL (the repo's own page is fine). Google Play requires one for any app using
   the accessibility service or `PACKAGE_USAGE_STATS`.

3. **Decide the store.**
   - **F-Droid**: no accounts, no fees, no policy review of the accessibility
     use. Needs the repo public with a tag per release and a
     `metadata/` description; F-Droid builds from source.
   - **Google Play**: requires the Accessibility API declaration form
     (Doorman's is "wellbeing / digital habits", `isAccessibilityTool="false"`),
     a prominent in-app disclosure (the service description string, plus the
     status card), a Data Safety form (see the table below), and a one-off
     $25 fee. Play may reject accessibility use it considers unjustified; the
     disclosure and `PRIVACY.md` are the argument.

## Every release

### 1. Bump the version

In `app/build.gradle.kts`:

- `versionCode` — **always +1**. Android refuses an update with the same or
  a lower number.
- `versionName` — what humans see. `1.0` → `1.1` for features, `1.0.1` for
  fixes.

### 2. Rules

Feeds get redesigned, and when they do a rule stops matching. Before each
release, open each held app on a device with the *current* store version and
check that the screens in `rules.json` are still recognised. Settings →
"Doesn't work?" → capture a report to read the live structure.

**A false positive — blocking a screen that should be open — is the fatal
failure.** Blocking a DM screen once teaches somebody to bypass every block.
When in doubt, the rule should match *less*, not more.

### 3. Strings

Every user-facing string exists in `values/`, `values-ca/` and `values-es/`.
`MissingTranslation` is a lint warning, not an error, so grep for it:

```bash
./gradlew lintDebug && grep -c MissingTranslation app/build/reports/lint-results-debug.sarif
```

Catalan and Spanish plurals need `one`, `many` and `other`; apostrophes must be
escaped as `\'`.

### 4. Verify

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Tests and lint must be clean (the two `AndroidGradlePluginVersion` notices are
expected). Then install the **release** build on a real device and check:

- [ ] The accessibility service can be enabled and the status card turns on.
- [ ] Each held app: the feed is covered, the inbox is not.
- [ ] A change delay actually delays; a pass actually opens.
- [ ] The weekly report tab renders; the notification channel exists.
- [ ] Turning the language chip recreates the app in that language.

**The accessibility service can come back switched off after an install.**
Seen repeatedly with `adb install -r` during development; store updates
normally keep it enabled, but some devices do not. Doorman checks on
`MY_PACKAGE_REPLACED` and posts a notification if the service is off
(`UpdateReceiver`); the status card is the fallback. Check both after
installing the release build over the previous version.

### 5. Ship

```bash
git tag -a v1.1 -m "…"
git push --tags
```

Attach `app/build/outputs/apk/release/app-release.apk` to the release (for
sideloading and for F-Droid's reproducibility check). Write a changelog entry
users can read: what changed *for them*, not which files.

## When the app's behaviour changes

Some changes are not just code:

| Change | Also update |
|---|---|
| Doorman records something new | `PRIVACY.md`, the accessibility disclosure string (`accessibility_service_description`, all three languages), the Play Data Safety form |
| A new permission | `PRIVACY.md`, the manifest comment explaining why, the tutorial if the user will meet it |
| A new held app | `rules.json`, the README status list, a `*RulesTest` |
| A new setting | The tutorial only if a newcomer needs it — the tutorial explains the shape of the app, not every switch |
| The coffee link or its cadence | `Support.kt` (`COFFEE_URL`, `COFFEE_NUDGE_DAYS`) |

## Data Safety form (Google Play)

For the record, so the form is filled in the same way every time:

| Question | Answer |
|---|---|
| Collects or shares data? | No — nothing leaves the device (no `INTERNET` permission) |
| Data stored on device | App settings; a 70-day activity journal (stops, budget time, loosenings, time per held app and per recognised screen); user-initiated diagnostics reports (redacted) |
| Encrypted in transit | N/A |
| Deletion | Uninstall; the journal can be switched off in Settings |
| Accessibility service purpose | Digital wellbeing — recognising which screen of a chosen app is open |
| Usage access purpose | Optional; shows time in held apps in the weekly report |

## What is deliberately not done

- **Shrinking (`isMinifyEnabled`) is off.** Screen labels are resolved by name
  from `rules.json`. Turning R8 on needs `res/raw/keep.xml` checked and a
  release build tested on a device first. The APK is small enough not to care.
- **No crash reporting.** It would need the internet permission, which would
  break the privacy argument this app is built on.
