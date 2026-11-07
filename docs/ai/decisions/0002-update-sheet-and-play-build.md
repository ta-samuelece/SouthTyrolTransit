---
type: Decision
title: Update sheet with per-release cards, then a separate Play Store build
description: Release cards, icons and translations travel in a separate whats-new.json because the installed version draws the sheet and old versions render the release body as text; categories are just the notes' headings; a later Play build is a real github/play variant split with the same package name and signing key, showing the notes after an update instead of before it.
tags: [updates, release, play-store, flavors, signing, i18n]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-05T22:30:00Z"
sources:
  - resource: "human:samuele, design review of 2026-11-05"
status: stable
---

# Context

The update prompt was a dialog showing the release's raw notes, in English only, in an app that runs in
three languages. The owner wants a richer sheet, and wants to publish on the Play Store as well as GitHub.
The two distributions behave differently: on GitHub the app updates itself, and on Play it may not.

# Decided: the sheet (stage 1, built)

- **Cards and translations live in their own file.** Each version has `<version>.md`, `.de.md`, `.it.md` and
  `.highlights.json` in `docs/release-notes/`; `tools/release.py` combines them into a `whats-new.json`
  asset. A marked-up release body was rejected: versions before 0.1.8 would show the markup as text.
- **Stable releases need all four files, previews only the `.md`.** Previews ship too often to translate.
- **Icons are a fixed list in the app**, with a sparkle for an unknown name. Sending icon shapes in the file
  would let old builds show new icons, at the cost of drawing code and a less controlled look.
- **Categories are the `##` headings of the notes**, with no fixed list: nothing to set up, and a list in
  the app would have the same old-build problem as icons.
- **No limit on the number of cards.** The version card and the buttons stay fixed and the cards scroll.
- **No count of further changes.** Cards are rewritten text and may merge bullets, so a count would be wrong.
- **The sheet stays open during a download**, so Android's installer never opens unannounced.
- **Stable users first see it going from the first version with it to the next one**, because the installed
  version draws the prompt. No one-time sheet was added for that; previews test it earlier.

# Decided: the Play build (stage 2, built in code; the store steps are the owner's)

The code and build side was built on 2026-11-07 ([store flavors](../notes/store-flavors-github-and-play.md)):
the two flavors, the "New in X" sheet, the bundle without language splits, the renamed Gradle tasks and the
generated Play "What's new". Still open, all on the owner's side: the Play account and closed test, Play
App Signing with the existing key and an upload key, the listing, `docs/PRIVACY.md`, and proving on a phone
that one build installs over the other.

- **Real build variants, `github` and `play`.** `update.repo=none` leaves the install permission and the
  updater code in the build, which Play restricts. The updater, `REQUEST_INSTALL_PACKAGES` and its file
  provider go only in `github`. The timetable prompt waits for the update prompt in `TransitApp.kt`, so `play`
  needs an equivalent signal. Variant names change Gradle tasks in `ci.yml`, `release.yml` and `CLAUDE.md`, the
  APK path in `tools/release.py`, and probably `filterVariants` for the licence list. Play needs a bundle with
  language splitting off, since the app has its own language picker.
- **Same package name, and the existing signing key uploaded to Play App Signing**, with a separate upload key.
  Only the owner handles any key (CLAUDE.md rule 10). Must be proven on a phone during the closed test before
  production, both ways: nothing found states directly that a Play build installs over a GitHub one.
  Anyone moving to Play loses the Preview channel and the updater.
- **After an update, Play shows "New in X"**, only for a real update to a version with cards: not on a fresh
  install (compare install and update times), and not on a hotfix without cards. Only that version's notes,
  so no "since X" wording. The GitHub build never shows it, since those users read the notes before updating.
- **Play's "All changes" has only "Got it"**, with no link to the release page, which offers the APK.
- **Tracks**: previews to closed testing, stable to production with a staged rollout. Same commit and version
  on both stores, without waiting for review; a hotfix goes to both. Uploads by hand at first, automated after
  the closed test.
- **Play's own "What's new" text is generated from the cards**, cut at a whole card at its limit.
- **Listing**: named South Tyrol Transit, "unofficial" in the short description and the first line, no STA
  or SASA logos, three languages. All foreground service features stay, with their declarations. A
  `docs/PRIVACY.md` in three languages is the public privacy page.

# What it costs

Each stable release needs its cards and two translations checked by the owner. A new icon reaches the sheet one
version late. Stage 2 adds a second build to every release and longer Gradle task names.

# To verify when stage 2 starts

From web sources of uneven quality, not checked on Google's own current pages: new personal accounts need a
closed test with 12 testers for 14 days; the exact wording of the self-update restriction; the character limits
of the store fields; and Android developer verification, reported to reach installs outside Play worldwide in
2027, which would also affect the GitHub build.
