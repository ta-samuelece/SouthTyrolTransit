---
type: Reference
title: The github and play store flavors
description: Since 2026-11-07 the app has two product flavors with one package name - github with the in-app updater, its install permission and file provider, play without them; shared code reaches them through two same-named functions per flavor; Play's "New in X" sheet shows once after a real update, decided by install versus update time and a recorded notesSeenVersion; every app Gradle task now names a flavor.
tags: [build, flavors, play-store, updates, release]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-07T23:30:00Z"
status: stable
---

Stage 2 of [the update sheet and Play build decision](../decisions/0002-update-sheet-and-play-build.md).
`app/build.gradle.kts` declares the dimension `store` with `github` (the default variant in Android
Studio) and `play`. Same `applicationId`, version and signing configuration, so one build installs over the
other when the key matches.

# What lives where

| Source set | Holds |
| --- | --- |
| `app/src/github/` | `AppUpdates.kt`, `UpdateUi.kt`, `GithubDistribution.kt`; a manifest with `REQUEST_INSTALL_PACKAGES` and the updater's FileProvider; `res/xml/update_paths.xml`. `BuildConfig.UPDATE_REPO` is a `github` field only. |
| `app/src/play/` | `PlayDistribution.kt`: the "New in X" logic and Settings' plain version row. |
| `app/src/testGithub/`, `app/src/testPlay/` | Their tests (`AppUpdatesTest`, `NewInVersionTest`). |

**The seam is two functions, not an interface.** Each flavor defines, in package
`org.southtyrol.transit.update`, `showUpdateStartPrompt(): Boolean` (draws the start-up sheet, returns
true while it shows; `TransitApp.kt` passes it to `TimetablePrompt(blocked = ...)`) and
`updateSettingsRows(switchRow): UpdateSettingsRows` (the Updates group in Settings). The class and the
`SwitchRowContent` type live in `app/src/main/.../update/UpdateSettingsRows.kt`. A function missing in one
flavor fails only that flavor's compile, so build both after touching it.

The update strings (`update_*`) stay in `src/main/res`, unused in `play`, so `TranslationsTest` keeps one
set of files; resource shrinking drops them from the Play build.

# "New in X" on Play

`NewInVersionViewModel` runs once per process: if `UserSettings.notesSeenVersion` is not the running
`versionName`, it records it **before** deciding, then shows the bundled notes sheet
(`WhatsNewReadSheet` with the hero "App updated") when `shouldShowNewIn` holds - `lastUpdateTime >
firstInstallTime` (a fresh install, also one with restored data, has them equal), and the build's own
`whats-new/highlights.json` has cards. Recording first means a crash or process death never brings the
sheet back on every start; the cost is that a sheet closed by process death is gone. Someone moving from
the GitHub build to Play sees it once, since that build never records the key.

# Gradle tasks

Every app task names a flavor: `assembleGithubDebug`, `:app:testGithubDebugUnitTest`,
`:app:lintGithubDebug`, `assembleGithubRelease`, `bundlePlayRelease`. A root `lintDebug` still succeeds but
only lints the core modules - the trap is a green run that skipped `app`. The release-notes task is now
`bundle<Variant>ReleaseNotes` per variant. The licence list's `filterVariants` names `githubRelease` and
`playRelease`.

Output paths: `app/build/outputs/apk/github/release/app-github-release.apk` (what `tools/release.py`
uploads) and `app/build/outputs/bundle/playRelease/app-play-release.aab`. `tools/release.py --play-notes FILE`
writes Play's "What's new" from the cards (whole cards up to 500 characters per language, in the Play
Console's `<en-US>` form); the Release workflow keeps that file and the bundle as the `play-upload`
artifact.

# What was checked

On 2026-11-07, in a worktree on Windows with JDK 23: the unit tests of both flavors (`NewInVersionTest`
included) and lint of both passed; `assembleGithubRelease`, `assemblePlayRelease` and `bundlePlayRelease`
built (unsigned, no key in the worktree). `aapt2` showed `REQUEST_INSTALL_PACKAGES` and the `.updates`
provider in the GitHub APK and in neither place in the Play APK; both release licence lists leave out
`ui-tooling`; `lintDebug --dry-run` from the root ran only `:core:*:lintDebug`. Not checked: the Play build on a device, the "New in X" sheet after a real Play update,
installing one flavor over the other, and Play App Signing - those need the owner's Play account.
