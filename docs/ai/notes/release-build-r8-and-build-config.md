---
type: Reference
title: Release build, R8 keep rules and build configuration mechanics
description: R8 keeps only GTFS-RT protobuf and MapLibre explicitly and relies on library consumer rules for serialization, navigation, Room and Hilt, so only assembleRelease exercises it; local.properties beats environment variables; no Kotlin Android plugin under AGP 9; Gson arrives transitively.
tags: [build, r8, proguard, gradle, release]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

# R8 / ProGuard

The release build has `isMinifyEnabled = true` and `isShrinkResources = true`. Explicit keep rules:

- `com.google.transit.realtime.**` - in `app/proguard-rules.pro` **and** `core/data/consumer-rules.pro`,
  which also keeps `* extends com.google.protobuf.GeneratedMessage`. The GTFS-RT bindings use full
  protobuf-java reflection; removing these breaks realtime parsing **in release builds only**.
- `org.maplibre.**` (JNI) in the app.

Nothing explicit for kotlinx-serialization, type-safe Navigation routes, Room, Hilt or WorkManager - those
rely on the libraries' bundled consumer rules, including private `@Serializable` classes. Tests run
unminified, so this path is exercised only by `assembleRelease`: after touching serialization, protobuf
or routes, install and smoke-test a release APK before publishing.

# Build configuration

- `BuildConfig` fields are read in `app/build.gradle.kts` as `local.getProperty(key) ?: System.getenv(env)
  ?: ""` - **`local.properties` wins over environment variables**. An empty update repo falls back to
  `ta-samuelece/SouthTyrolTransit`.
- No module applies the Kotlin Android plugin; AGP 9 compiles Kotlin itself (only `core:model` applies
  `kotlin.jvm`). Do not add `org.jetbrains.kotlin.android`. KSP is versioned independently of Kotlin.
- Lint runs with `checkDependencies = true` in `app`, so `lintDebug` covers the core modules too.
- `MapLibreTransitMap.kt` imports `com.google.gson.JsonObject`, which no catalog entry declares - it
  comes transitively through MapLibre and would break if MapLibre dropped it.
- `material-icons-extended` is pinned separately (1.7.8), not to the `compose` version.

# What was checked

Code read of the Gradle files, `proguard-rules.pro` and `consumer-rules.pro` on 2026-10-09; the toolchain
versions in README section 19 match the build files.
