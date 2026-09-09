# Doorman — Privacy Policy

_Last updated: 2026-09-09_

Doorman is a free, open-source Android app that covers endless feeds (Reels,
Shorts, the TikTok feed) while leaving messages, searches and individual videos
alone. It is made by one person, in their spare time, and is not a business.

The short version: **Doorman has no internet permission. Nothing it sees or
records can leave your phone, because the app has no way to send it.**

## What Doorman can see

To recognise which screen is open, Doorman uses Android's accessibility
service. This lets it read the *structure* of the screen in front of you — the
names and layout of the elements — for any app, because Android does not allow
an accessibility service to be limited to some apps and not others.

Doorman discards events from every app except the ones you have chosen to hold,
before reading anything about them. For the apps you hold, it matches the
screen's structure against a set of rules (`rules.json`) to decide whether the
screen is a feed. It does not read, interpret or store the text of messages,
posts, passwords or account details. Doorman's rules match on element names and
layout only; they never match on text.

## What Doorman records

Everything below is stored on your phone, in the app's private storage, and
nowhere else.

- **Your decisions**: which screens are held, budgets, delays, language.
- **A daily journal**: how many times Doorman stopped you, how much time was
  counted against a budget, and how many limits you weakened. One row per day,
  kept for 70 days, then deleted. This feeds the weekly report and can be
  switched off in Settings → Weekly report, which also stops recording.
- **A diagnostics report, only if you make one**: from Settings → "Doesn't
  work?", you can capture the structure of a screen to help fix a broken rule.
  All text and content descriptions are replaced with `<redacted>` before the
  file is written. Nothing is sent anywhere; the file exists so *you* can
  choose to share it. Read it before you do.

Doorman used to keep a list of which apps it had seen you open, to offer them
in a picker. It no longer does, and deletes that list from older installs.

## Optional: Android's usage figures

If you turn it on from the weekly report, Doorman can read Android's own
screen-time figures (the "usage access" permission) to show how much time you
spent in the apps you hold. This permission is wide — Android grants it for
every app — but Doorman reads it only for the apps you have chosen to hold,
only while drawing the report, and stores none of it. The report works without
it. Nothing else in the app changes if you decline.

## Notifications

If enabled, Doorman posts one notification a week, on Monday morning, with a
summary of the week. It can be switched off in Settings → Weekly report.

## Backup

Android may back up Doorman's settings and journal to your Google account as
part of the system's automatic app backup, so they come back on a new phone.
Diagnostics reports are excluded from backup.

## What Doorman does not do

- No internet permission, so no analytics, telemetry, crash reporting, ads, or
  accounts.
- No reading of message contents, passwords, or form fields.
- No selling, sharing, or transmitting of anything.

## Contact

Open an issue on the project's repository, or reach the author through
<https://buymeacoffee.com/eloiprat>.

## Changes

This policy changes only when the app's behaviour changes. The date at the top
is the record.
