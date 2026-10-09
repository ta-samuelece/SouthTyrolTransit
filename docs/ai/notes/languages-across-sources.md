---
type: Reference
title: Languages - how each source is asked, how texts fall back, and how to add a UI language
description: Ladin users see German GTFS names but Italian EFA results; never pass the comma-separated textLanguages() to EFA; localized() prefers a language-neutral text over fallbacks; the seven places a new UI language must be registered.
tags: [localization, ladin, efa, gtfs, translations]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

README section 15 states the user-facing behaviour for a single language. This is the mechanics.

# Per source (`Languages` in `core/model/.../Text.kt`)

| App language | GTFS names (`translationLanguage`) | EFA requests (`Languages.efa`) | EFA StopFinder |
| --- | --- | --- | --- |
| `de` | German | `en`* | `de` |
| `it` | Italian (base) | `it` | `it` |
| `lld` | German (via fallbacks) | `it` | `it` |
| `en` | Italian | `en` | `de` |

\* `Languages.efa` maps `it`/`lld` to `it` and everything else to `en`; README section 16 explains why
StopFinder queries de and it in parallel.

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

1. `AppLanguage.Option` (tags string; Ladin is `"lld,de,it"`) and the `when` in `AppLanguage.selected()`.
2. `languageLabel` in `SettingsScreens.kt`.
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
2026-10-09.
