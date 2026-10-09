---
type: Reference
title: App icons and the package name are wired in more places than the README says
description: generate_icons.py (needs Pillow and numpy) only writes images; a new icon also needs a manifest activity-alias, an AppIcon entry and three strings; alias names are permanent; the package name is hard-coded in the icon switcher, the widget and MainActivity.
tags: [icons, launcher, manifest, package-name, tools]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

README section 1 says to run `tools/generate_icons.py` after adding an icon. That is only step two.

# Adding an icon, end to end

1. Put the PNG in `icons/`. Its slug is the file name in snake case.
2. Run `python tools/generate_icons.py` from the repo root. It needs **Pillow and numpy**
   (`pip install pillow numpy`). It writes only the `mipmap-<density>/ic_launcher_<slug>_foreground.png`
   layers, `mipmap-anydpi/ic_launcher_<slug>.xml` and `drawable-nodpi/icon_preview_<slug>.png`.
3. Add an `<activity-alias android:name=".Icon<Pascal>" android:enabled="false"
   android:icon="@mipmap/ic_launcher_<slug>">` with a MAIN/LAUNCHER filter to `AndroidManifest.xml`.
4. Add an entry to `enum class AppIcon` in `app/src/main/kotlin/org/southtyrol/transit/ui/AppIcons.kt` -
   the Settings picker lists `AppIcon.entries`.
5. Add `icon_<slug>` to `values`, `values-de` and `values-it` (`TranslationsTest`).

# Treat alias names as permanent

`MainActivity` has no launcher filter - only the aliases do. `AppIcons.set` explicitly disables every alias
but the chosen one, and `AppIcons.current` treats the manifest default state as `AppIcon.DEFAULT`. So:

- the alias with `android:enabled="true"` (`.IconAbstractSilhouette`) must be `AppIcon.DEFAULT`;
- Abstract Silhouette is also hard-coded in `mipmap-anydpi/ic_launcher.xml`, the splash
  `windowSplashScreenAnimatedIcon` (`values/styles.xml`) and the widget's `previewImage`;
- renaming or removing an alias a user has selected most likely leaves the app with **no launcher entry**
  after the update (inferred). Add new aliases; don't rename old ones.

# The package name is not only in two files

README section 1 says to rename the placeholder in `app/build.gradle.kts` and `strings.xml`. It is also
hard-coded in `AppIcons.component` (`"org.southtyrol.transit." + icon.alias`), in `MainActivity`'s
`EXTRA_STOP_KEY`/`EXTRA_STOP_NAME`, and in `android:configure` of
`app/src/main/res/xml/departures_widget_info.xml`. Renaming the namespace without these breaks the icon
switcher and widget configuration. The in-app updater's FileProvider authority is
`${applicationId}.updates` and must match `"${context.packageName}.updates"` in `installIntent`.

# What was checked

The script's imports, the 12 activity-aliases (six icons) and `AppIcons.component` re-read on 2026-10-09.
