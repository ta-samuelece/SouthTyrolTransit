---
type: Reference
title: In-app updater internals the README does not cover
description: Hourly in-memory check throttle, the lenient certificate comparison, the three coupled updates/ paths and the startup cleanup of installed downloads, how version strings compare (build metadata and rc suffixes), how the update sheet gets its cards, and what holds it open during a download.
tags: [updates, release, versioning, fileprovider]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-31T10:00:00Z"
status: stable
---

README "In-app updates and publishing a release" describes the user-visible flow. Code:
`app/src/main/kotlin/org/southtyrol/transit/update/AppUpdates.kt`, tests in `AppUpdatesTest`.

- **Throttle**: checks run at most once an hour (in-memory `lastCheck`) unless forced. The Preview channel
  reads only `releases?per_page=30`. A 404 counts as "up to date".
- **Certificate check is lenient**: the update is refused only if both certificate sets are readable and
  share nothing. If either side cannot be read, the decision is left to Android's installer.
- **Coupled paths** - change all three together: `File(context.cacheDir, "updates")`,
  `<cache-path name="updates" path="updates/" />` in `res/xml/update_paths.xml`, and the FileProvider
  authority `${applicationId}.updates`. `download` deletes everything in `cacheDir/updates` first, and
  `UpdateManager`'s `init` deletes every file there that `isStaleUpdateFile` rejects (not named
  `SouthTyrolTransit-<version>.apk` with a version newer than the running one), so an installed update
  does not linger. The name pattern is shared through `APK_PREFIX`; keep the two in step.
- **Version comparison** (`Versions.compare`) splits on `-` or `+`: build metadata (`1.0.0+x`) ranks
  *below* `1.0.0`, and non-numeric suffixes compare as strings (`rc` > `preview`). Stick to `X.Y.Z` and
  `X.Y.Z-preview.N`.
- Downloads run on `UpdateManager`'s own `SupervisorJob` scope, so they survive leaving Settings.
- **Two ViewModels, one state**: `UpdatePrompt` is Activity-scoped (outside the `NavHost`), the Settings
  section lives in the Settings back-stack entry. Everything shared, "Later" included, is a flow in
  `UpdateManager`; `shouldPrompt` decides whether the sheet shows.
- **The sheet is drawn by the installed version, about a newer one.** So its cards and translated notes
  come over the network, from the `whats-new.json` asset `tools/release.py` attaches, and the sheet knows
  only the icons of its own build. The GitHub release body stays plain English Markdown, because versions
  before 0.1.8 render it in their old dialog; anything hidden in it would show there as text. The asset is
  uploaded before the APK, since a release is offered only once it has an APK. Fetching is best effort
  (10 s): failing falls back to the body. The Settings entry reads the build's own copy instead, which the
  `BundleReleaseNotes` task in `app/build.gradle.kts` puts in the assets as `whats-new/`.
- **During a download the sheet needs `sheetGesturesEnabled = false`.** `confirmValueChange` alone is not
  enough: a fast swipe still sometimes hid it, and it then stayed hidden when the download ended, with
  `shouldPrompt` true and nothing on screen. Seen on the emulator with material3 1.5 alpha.

# What was checked

Code read of `AppUpdates.kt`, `UpdateUi.kt` and `update_paths.xml` on 2026-10-09; startup cleanup added
2026-10-31 with a test in `AppUpdatesTest` (written without an Android SDK, so not yet run, nor tried on a device).
The sheet was walked through on the `Screenshots_36` emulator on 2026-11-05, with a temporary build posing as
0.1.6 that read its cards from its own assets: offer, all changes, back, download, swipe and Back during it,
failure, "Later", Settings' two entries, German, a short screen. Not seen on a device: the "Ready" state, a
real `whats-new.json` download and the preview label, which need a published release.
