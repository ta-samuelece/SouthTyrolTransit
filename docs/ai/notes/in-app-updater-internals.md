---
type: Reference
title: In-app updater internals the README does not cover
description: Hourly in-memory check throttle, the lenient certificate comparison, the three coupled updates/ paths, how version strings compare (build metadata and rc suffixes), and why the update dialog and the Settings section can disagree.
tags: [updates, release, versioning, fileprovider]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
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
  authority `${applicationId}.updates`. `download` deletes everything in `cacheDir/updates` first.
- **Version comparison** (`Versions.compare`) splits on `-` or `+`: build metadata (`1.0.0+x`) ranks
  *below* `1.0.0`, and non-numeric suffixes compare as strings (`rc` > `preview`). Stick to `X.Y.Z` and
  `X.Y.Z-preview.N`.
- Downloads run on `UpdateManager`'s own `SupervisorJob` scope, so they survive leaving Settings.
- **Two ViewModels**: `UpdatePrompt` is Activity-scoped (outside the `NavHost`), `UpdateSettings` lives in
  the Settings back-stack entry. "Later" is copied into each VM's own `dismissed` flow, so after "Later" a
  manual check from Settings shows Settings' own button, not the app-wide dialog. Shared update state
  belongs in `UpdateManager` as a flow.

# What was checked

Code read of `AppUpdates.kt`, `UpdateUi.kt` and `update_paths.xml` on 2026-10-09.
