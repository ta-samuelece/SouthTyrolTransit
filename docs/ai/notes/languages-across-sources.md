---
type: Reference
title: Languages - how each source is asked, how texts fall back, and how to add a UI language
description: The app speaks en, de and it (Ladin was removed as a UI language on 2026-10-21; its feed texts still normalise to lld); names use a second, @NamesLanguage provider from the Stop names setting while alerts and notices keep the app language; never pass the comma-separated textLanguages() to EFA; localized() prefers a language-neutral text over fallbacks; the seven places a new UI language must be registered.
tags: [localization, efa, gtfs, translations]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-20T12:00:00Z"
status: stable
---

"Localization" in `docs/DEVELOPMENT.md` states the user-facing behaviour for a single language. This is the mechanics.

# Per source (`Languages` in `core/model/.../Text.kt`)

| Language | GTFS names (`translationLanguage`) | EFA requests (`Languages.efa`) | EFA StopFinder |
| --- | --- | --- | --- |
| `de` | German | `de` | `de` |
| `it` | Italian (base) | `it` | `it` |
| `en` | Italian | `en` | `de` |

`Languages.efa` maps `de` to `de`, `it` (and `lld`, a leftover of the removed Ladin UI) to `it` and
everything else to `en`; "Known API limitations" in `docs/DEVELOPMENT.md` explains why StopFinder queries de and it in parallel. `Languages` in
`Text.kt` still knows `lld` because feeds carry Ladin texts; no UI choice produces it any more.

# Two providers: app language and names language

`LanguageProvider` has two bindings in `AppModule`. The unqualified one is `AppLanguage` (the UI
language). The one qualified `@NamesLanguage` (`app/.../di/NamesLanguage.kt`) resolves the "Stop names"
setting (`UserSettings.nameLanguage`: `APP`, `GERMAN`, `ITALIAN`) with `NameLanguage.resolve`: `APP`
returns `AppLanguage.current()` unchanged, so the default changes nothing; the others return `de` or `it`.
It keeps the choice in memory, follows the DataStore flow, and reads it once, blocking, on first use, so a
cold widget or follow update gets the stored choice.

- Names provider: every stop, place, line and trip lookup - Stop, Trip, Lines, Map, the pickers and
  `SavedStopsFlow`, Places, MapSearch, Planner, the journey view models, `FollowEngine`, `RideEngine` and
  `WidgetUpdateWorker`.
- App language: `AlertsViewModel` (including stop names in alert scopes), the alert refresh in `Workers.kt`
  and `TripViewModel`, alert cards (`textLanguages()`) and all `R.string` UI. `TripViewModel` gets both.
- Journey notices: the trip request goes out in the names language, so `EfaXml.journeys` gets the app
  language as `noticeLanguage` (passed down from `JourneyRepository.plan` and `EfaClient.plan`). A leg keeps
  the untagged `infoLink`s and those tagged `Languages.efa(noticeLanguage)`; when none is tagged so, those
  in the response's root `language`. Null, or the same EFA language as the response, gives the old result.

# Language lists

- `AppLanguage.current()` returns one tag; `textLanguages()` returns a list such as `en,de-DE,...` and is
  meant only for `localized()` on alert texts. **Never pass it to EFA**: `Languages.normalize` does not split
  commas, so `Languages.efa("it,de")` silently becomes `en`. Passing it to GTFS queries would change stop
  names, because `fallbacks` does split commas.

# Text fallback (`Map.localized`)

Order: the requested language, then the language-neutral `""` key, then the network fallbacks
(`lld -> de, it, en`; `de -> en, it`; `it -> en, de`; other `-> en, it, de`), then anything. Blank values
are skipped (pinned in `TextTest`). Keys `ld1`/`ld2`/`lad` normalise to `lld`, so if EFA's two Ladin
variants differ, the later one wins silently. A GTFS-RT translation without a language tag beats every
fallback except the exact match.

# Adding a UI language

1. `AppLanguage.Option` (tags string, may be a fallback list such as `"x,de,it"`) and the `when` in
   `AppLanguage.selected()`.
2. `languageLabel` in `SettingsScreens.kt` and `OnboardingScreen.kt` (with its `languages` list), and
   `languageName` and the order in `languageItems` in `SearchBarMenu.kt` (the map menu, which says
   "System" via `language_system_short` where Settings says "System default").
3. `app/src/main/res/xml/locales_config.xml` (Android 13+ system picker).
4. `values-<x>/strings.xml` in `app` and `core/designsystem`.
5. `TranslationsTest`'s `listOf("de", "it")` if the language must be complete.
6. `Languages` in `Text.kt`: `supported`, `normalize`, `efa`, `efaStopFinder`, `fallbacks`.

Per-app locales go through `AppCompatDelegate.setApplicationLocales`, which recreates the activity. On
Android 12 and lower this depends on `MainActivity` being an `AppCompatActivity` and the manifest's
`AppLocalesMetadataHolderService` with `autoStoreLocales`. Workers and the plain-`ComponentActivity`
widget config may then see the system language (inferred, not tested).

# What was checked

Code read of `Text.kt`, `AppLanguage.kt`, `GtfsSchedule.translationLanguage` and `TextTest` on
2026-10-09. The two providers and the notice rule were written on 2026-11-20 (`NameLanguageTest`); not
tried on a device. Ladin removal (`AppLanguage.dropLadin` at app start moves `lld` users to German) on
2026-10-21; not tried on a device.
