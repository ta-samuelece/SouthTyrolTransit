---
type: Reference
title: Test suite layout, and the network tests that are off by default
description: Four kinds of tests across three modules; no screen or ViewModel is tested despite the README; the tests that download the real STA feed or hit the FTP server only run with -PliveTests=true or -PgtfsReal; TranslationsTest fails on any missing or stale en/de/it string.
tags: [testing, robolectric, translations, gtfs, build]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
status: stable
sources:
  - id: data-build
    resource: ../../../core/data/build.gradle.kts
    title: core:data test system properties
  - id: translations
    resource: ../../../app/src/test/kotlin/org/southtyrol/transit/TranslationsTest.kt
    title: TranslationsTest
  - id: readme
    resource: ../../../README.md
    title: README section 11, Tests
---

README section 11 lists what the tests cover and the commands.[^readme] This note records what it leaves
out.

# Where tests live

| Kind | Where | Runs with |
| --- | --- | --- |
| Pure JVM unit | `core/model/src/test/` | `./gradlew :core:model:test` |
| Android unit (Robolectric, MockWebServer, fixtures) | `core/data/src/test/` | `./gradlew :core:data:testDebugUnitTest` |
| Compose UI (Robolectric) and resource checks | `app/src/test/` | `./gradlew :app:testDebugUnitTest` |
| Instrumented | `app/src/androidTest/` | `./gradlew :app:connectedDebugAndroidTest` - needs a device or emulator |

Fixtures are real captured API payloads in `docs/fixtures/`; the instrumented tests carry their own
copies in `app/src/androidTest/assets/`.

# What `app/src/test` really covers

README section 11 says the app's Robolectric UI tests cover "journey search and result selection, stop
departures, saving a stop". As of 2026-10-09 they are `UiComponentsTest` (stateless composables),
`AppUpdatesTest`, `MarkdownTest` and `TranslationsTest`; "journey search" is a `RequestCodec` round trip,
and **no screen, ViewModel or NavHost is tested** and no test saves a stop.

Conventions: `app/src/test/resources/robolectric.properties` sets `sdk=35` (not 37) and
`application=android.app.Application`, which bypasses Hilt and `TransitApplication`. Tests render
composables inside `TransitTheme(dynamicColor = false)` with `@Config(qualifiers = "en")`. Anything that
needs `hiltViewModel()` cannot be tested this way without adding Hilt test infrastructure.

# Opt-in tests that use the network or a real feed

`core/data/build.gradle.kts` passes two Gradle properties into the test JVM.[^data-build] Tests guarded by
them are skipped (JUnit `assumeTrue`), not failed, when the property is absent - so a green run does not
mean they ran.

| Property | System property | Enables |
| --- | --- | --- |
| `-PliveTests=true` | `live.tests` | `FtpLiveTest` (HEAD of the real file on the STA FTP server) and `LiveDownloadTest` in `RealFeedTest.kt` (full FTP download and import, up to 30 minutes) |
| `-PgtfsReal=<path to zip>` | `gtfs.real` | `RealFeedTest`'s import of a locally downloaded ~150 MB STA feed |

Only `-PgtfsReal` is in the README. Never enable `liveTests` in an automated loop: it downloads the full
feed from STA's server.

# Translations are enforced by a test, not by lint

`TranslationsTest` compares string keys of `values/` against `values-de/` and `values-it/`, for `app`
and `core/designsystem`, and fails on a key that is **missing** or **stale** (present only in a
translation).[^translations] Lint's `MissingTranslation` is downgraded to a warning on purpose, because
Ladin (`values-b+lld/`) is deliberately partial and falls back to German through the locale list
`lld,de,it`. So: adding, renaming or removing a string means touching all three files.

# What was checked

Read `core/data/build.gradle.kts`, `FtpLiveTest.kt`, `RealFeedTest.kt`, `TranslationsTest.kt` and the lint
block of `app/build.gradle.kts` on 2026-10-09; listed `app/src/test` and its `robolectric.properties` the
same day. The tests were not run.

[^readme]: README section 11
[^data-build]: core:data test system properties
[^translations]: TranslationsTest
