# Development

How South Tyrol Transit is built and how it deals with its data. Publishing a build is in [RELEASING.md](RELEASING.md).

## Setup

Requirements:

- JDK 21. The Android Studio bundled JBR works.
- Android SDK with platform **android-37.1** and build-tools 37.

Steps:

1. Point `local.properties` at your SDK: `sdk.dir=...`.
2. Optionally add map style overrides:
   ```properties
   # local.properties (never commit secrets)
   map.styleUrl=https://example.org/style.json
   map.styleUrlDark=https://example.org/dark.json
   ```
   Environment variables `TRANSIT_MAP_STYLE_URL` and `TRANSIT_MAP_STYLE_URL_DARK` also work.

## Map and API keys

**No keys are required.** All sources are keyless. If you switch to a keyed tile provider, put the full style URL (including the key) in `local.properties` or an environment variable. It is injected into `BuildConfig` and never committed: `local.properties` is git-ignored.

## Build

```bash
./gradlew assembleGithubDebug      # app/build/outputs/apk/github/debug/app-github-debug.apk
./gradlew assembleGithubRelease    # R8-minified; configure signing first
./gradlew bundlePlayRelease        # the Play bundle: app/build/outputs/bundle/playRelease/app-play-release.aab
./gradlew :app:lintGithubDebug :app:lintPlayDebug   # Android Lint (abortOnError)
```

The app has two **store flavors** with the same package name and signing key, so either installs over the other:

| Flavor | For | Updates |
|---|---|---|
| `github` (default) | APKs on GitHub Releases | the in-app updater (`app/src/github/`): it alone has `REQUEST_INSTALL_PACKAGES` and the updater's file provider |
| `play` | Google Play, as an app bundle | Play's own; after an update to a version with highlight cards, a "New in X" sheet shows them once (`app/src/play/`) |

Shared code reaches the store-specific part through two functions each flavor defines under `update/` (`showUpdateStartPrompt`, `updateSettingsRows`; see `UpdateSettingsRows.kt`). The Play bundle has language splitting off, since the app has its own language picker. Name the flavor in Gradle task names: the app's flavorless ones, such as `:app:lintDebug` or `:app:testDebugUnitTest`, no longer exist.

The release APK carries native code (MapLibre) only for ARM (`arm64-v8a`, `armeabi-v7a`), which is every phone; leaving out x86/x86_64 saves about 22 MB. Debug builds keep all four, so they still run on an x86_64 emulator. To try a release build on an emulator, use an ARM64 system image or one with ARM translation.

## Tests

```bash
./gradlew :core:model:test :core:data:testDebugUnitTest :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:connectedGithubDebugAndroidTest     # on a device/emulator
# Optional: import the real 150 MB STA feed and join it with captured realtime
./gradlew :core:data:testDebugUnitTest --tests '*RealFeedTest*' -PgtfsReal=/path/to/google_transit_shp.zip
```

- **Unit (`core:model`)**:
  - GTFS times beyond 24:00
  - calendar and calendar_dates logic
  - DST spring/fall
  - Europe/Rome independent of device zone
  - realtime delay, early running, propagation, NO_DATA, cancellation, skipped stops, staleness
  - vehicle freshness
  - multilingual fallback incl. Ladin
  - alert dedupe and validity
  - polyline, simplification, contrast
- **Unit (`core:data`)**:
  - CSV edge cases
  - EFA parsing of trip, walking, fares, DM, coord, AddInfo, StopFinder, error -8020, DTD rejection and malformed XML, using real captured fixtures
  - trip parameter building, including date/time in Europe/Rome
  - GTFS-RT trip updates, vehicles and multilingual alerts
  - mobility freshness, charging stations joined from real captured station, point and status payloads, bike boxes and e-bike sharing stations from real captured site and value payloads, and parking forecasts built from a real captured forecast and history
  - MockWebServer: retries, 404, 429 with Retry-After, 5xx, timeouts, disconnects, malformed and empty payloads
  - importer joins, past-midnight trips, calendar exceptions, translations, terminating trips, atomic swap and rejection of broken feeds (Robolectric)
  - network-board fallback, realtime/alert merge and stale handling, offline place search, cached alerts going stale offline, saved items
- **UI components (Robolectric Compose, `app/src/test`)**: individual components rather than whole screens - the journey search request surviving navigation, a journey result card, a departure row (countdown, delay, opening the trip, cancellation label), an alert card (expanding, switching language), the offline and location-denied states, the first-run screens' frame (back, skip, no progress bar on the summary). No screen or ViewModel is tested end to end.
- **Other app unit tests**: translations complete in en/de/it, the in-app updater's version and release parsing, release-notes Markdown, licence text formatting.
- **Instrumented (`app/src/androidTest`)**: EFA parsers on the device's XML stack, plus a developer helper that imports a pushed GTFS zip:
  ```bash
  adb push google_transit_shp.zip /data/local/tmp/sta.zip
  adb shell am instrument -w -e feed /data/local/tmp/sta.zip -e class org.southtyrol.transit.ImportFeedOnDevice org.southtyrol.transit.test/androidx.test.runner.AndroidJUnitRunner
  ```

Fixtures live in `docs/fixtures`.

## Branches and pull requests

`main` only changes through pull requests, and CI ("Tests and lint") must pass before one can be merged. Name your branch `<type>/<short-description>`: the type is `feature/`, `fix/`, `chore/` (build, tooling, CI, releases) or `docs/`, followed by a few lowercase words describing the change, joined by dashes, e.g. `feature/places-cards` or `fix/search-reload-loop`. CI fails a pull request from any other branch name; rename the branch (`git branch -m <new-name>`) and open a new pull request.

## Architecture overview

- Kotlin, Jetpack Compose (Material 3 Expressive), coroutines and Flow/StateFlow, ViewModel.
- Type-safe Navigation Compose, Hilt (KSP), Room, DataStore, WorkManager, OkHttp, kotlinx-serialization, GTFS-Realtime protobuf bindings, MapLibre.
- **Layers.** UI (screens + ViewModels) → repositories → data-source interfaces → implementations. ViewModels only see domain models (`core:model`) and never know whether data came from GTFS, GTFS-RT or EFA.
- **Interfaces** (`core/model/.../Interfaces.kt`):
  - `TransitScheduleDataSource` (GTFS)
  - `RealtimeTransitDataSource` (GTFS-RT)
  - `JourneyPlannerDataSource`, `GeocodingLocationDataSource`, `DepartureBoardDataSource`, `AlertDataSource` (EFA)
  - `MobilityDataSource` (ODH)
  - `TicketingProvider` (official link only)
  - `PushBackend` (no-op until a server exists)
- **Merging.** `DepartureRepository`, `TripRepository` and `AlertRepository` combine static schedule, realtime snapshot and alerts. `RealtimeMerge` implements GTFS-RT delay propagation, cancellation, skipped stops and staleness. `FreshnessPolicy` holds the explicit rules.
- **Live screens** poll through `PollWhileVisible` (`repeatOnLifecycle(STARTED)`), so nothing polls in the background. Per-feed throttling in `RealtimeRepository` prevents duplicate requests when several screens are open.
- **Map** is provider-agnostic: features use `TransitMap(MapContent…)`. MapLibre lives in one file (`MapLibreTransitMap.kt`). The project uses the OpenGL ES build (`org.maplibre.gl:android-sdk-opengl`): the default Vulkan build crashed the Android emulator's software GPU and is riskier on older devices at minSdk 26.

## Modules

| Module | Contents |
|---|---|
| `core:model` | Pure Kotlin/JVM: domain models, GTFS time and calendar logic, realtime merge, freshness policy, multilingual fallback, alert dedupe, geo/polyline/contrast utilities, data-source interfaces |
| `core:data` | Android library: network (`TransitHttp`), CSV, GTFS importer and schedule store (Room, one DB file per feed version), GTFS queries, EFA client/parsers, GTFS-RT parser and repository, ODH mobility, repositories, DataStore settings |
| `core:designsystem` | Theme (`MaterialExpressiveTheme`, fallback palette, motion schemes), status colors, mode colors/shapes, components (line badges, countdown, delay/freshness labels, banners, expressive loading, empty/error states, connected toggle group), formatting |
| `core:map` | `TransitMap` API and MapLibre implementation |
| `app` | Application, DI, navigation, feature screens (`feature.*` packages), workers, location |

Features are packages inside `app` rather than modules. That keeps AGP configuration and build times small; splitting them out later is mechanical.

## Public APIs and data sources

See **[docs/API_DISCOVERY.md](API_DISCOVERY.md)** for endpoints, fields, refresh behaviour, error handling, confidence and what is deliberately not used. In summary:

- **Static GTFS**: `gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw`, with a fallback to STA's own `ftp.sta.bz.it` publication point.
- **GTFS-Realtime**: `files.opendatahub.com/gtfs-rt/feeds/sta/*.pb`, the sources listed in ODH `datasets.yml`, with a fallback to `gtfs.api…/v1/realtime`.
- **STA EFA XML**: `efa.sta.bz.it/apb/`, covering StopFinder, Trip, DM, Coord and AddInfo.
- **ODH Mobility v2**: parking, bike sharing, car sharing, EV charging stations (stations, their charging points and each point's status, joined in the app), STA's Bicibox bike boxes and Ecospazio's e-bike sharing (sites and their latest values, joined in the app).
- **OpenFreeMap** tiles: the standard map is Positron in light mode, Fiord in dark mode and Dark Matter with pure black on (one design in three colourings); the Detailed map type is Liberty in every theme, as it has no dark variant.
- **Province of Bolzano WMTS** (`geoservices.buergernetz.bz.it`): the 2023 orthophoto and the topographic map for the Aerial and Topographic map types.

## Licences

- STA timetable, realtime and EFA data: **CC0**. Credited anyway on the About screen.
- ODH Mobility: open data published by Open Data Hub.
- Map: OpenFreeMap tiles; data © OpenStreetMap contributors (**ODbL**). Aerial and topographic maps: Autonomous Province of Bolzano geodata (**CC0**). Attribution is shown in the map and on the About screen.
- App code: public domain via **[The Unlicense](../LICENSE)**. Use, copy, modify, sell, relicense: no conditions, no attribution required. (The data licences above still apply to the data.)
- Libraries: every library in the release APK is under a permissive licence - Apache 2.0 (AndroidX, Kotlin, kotlinx, Dagger/Hilt, OkHttp, Okio, Gson, Guava, Timber, GTFS-realtime bindings, MapLibre parts) or BSD (MapLibre Android SDK, protobuf, parts of AndroidX). That is compatible with releasing the app's own code under the Unlicense. Apache 2.0 and BSD ask that licence texts and copyright notices ship with the APK: the app lists every library with its licence under Settings → Open-source licences (also linked from About). The list is generated at build time by the AboutLibraries Gradle plugin; the real BSD notices (with copyright lines) come from `app/licenses-config/`.

## Static GTFS update strategy

1. **Schedule.** `ScheduleSyncWorker` (WorkManager) runs weekly, plus once as soon as possible on installs that have no timetable yet, with battery not low and storage not low, and on unmetered network unless "Download over mobile data" is on. The user can "Download now" over any network. A downloaded feed that fails validation is not retried; network failures are retried up to three times. Each time the app opens (except right after the first-run screens), it also checks the timetable itself: when it is missing or cannot be read, when its last service day has passed, or when the HTTPS source reports a newer feed than the installed one (a conditional request whose body is never read, compared by ETag or Last-Modified) or the importer changed since it was built, a dialog offers "Download now" (any network) or "Later" (until the next app start). An expired timetable is not offered again when the server confirms there is nothing newer, nor while a download is running; the dialog waits for the app-update offer, if one is showing.
2. **Download.** Over HTTPS the request is conditional (ETag / Last-Modified) when the previous feed came from the same source; the download has no overall time limit, only a read timeout, so slow connections can finish. If the HTTPS source fails and the setting allows it, the STA FTP mirror is used; FTP downloads are always complete (no conditional request) and resume after dropped connections.
3. **Skip unchanged feeds.** If the SHA-256 is unchanged and the database was built by the current importer (`GtfsImporter.VERSION`), nothing is imported. Bumping that version makes every install re-import its current feed on the next sync, and makes the start-up check offer that download to every user.
4. **Import.** The feed is streamed into a **new SQLite file**:
   - Integer surrogate keys and indexes for stop, trip, station and stop-time lookups, plus an FTS4 stop-name index (all languages, accent-folded).
   - Shapes are simplified (Douglas–Peucker, 4 m) and stored as encoded polylines.
   - Headsign translation gaps are filled with a text dictionary.
5. **Validate.** The import checks required files, the timezone and referential integrity (more than 1 % broken stop_times is rejected).
6. **Activate.** An atomic pointer rename switches to the new file and old files are deleted. An interrupted or failed import never touches the working schedule.
7. **Real feed (4 Oct 2026):** 6,436 stops, 57,316 trips, 1.1 M stop_times. 15 s import on desktop (expect 1–3 min on a phone; the sync shows an ongoing notification without a percentage, and Settings shows the download and import progress). 96 MB database. Departure board query about 60 ms.

## Realtime update strategy

- Trip updates and vehicle positions are fetched as protobuf **only while a live screen is visible**: 30 s on boards and lines, 20 s on map and trips. They are throttled to at most every 20 s per feed across screens.
- Where GTFS-RT has no prediction, boards and trips fall back to the live times of the STA journey planner (EFA). This is the only live source for trains: STA's GTFS-RT feeds carry buses only (checked 24 Oct 2026). A trip opened without a planner reference (from a line page, for example) finds its run on the planner's board at its next station, at most every 2 min while the planner has nothing for it and never while GTFS-RT covers it.
- No open source gives train positions, so trains never appear as vehicles on the map. The trip screen places a train by its live or scheduled times and says it is an estimate.
- Alerts are fetched at most every 2 min (GTFS-RT) and 10 min (EFA AddInfo), and cached on disk.
- Freshness rules (`FreshnessPolicy`):
  - Trip predictions older than 5 min are ignored.
  - A feed not fetched for 3 min counts as unavailable.
  - Vehicles are dimmed after 2 min and hidden after 10 min.
- Every live value shows its age. When data is stale the UI shows scheduled times and says so.

## Offline behaviour

Available offline:

- downloaded timetable: stop and line search, nearby stops, departure boards (scheduled), trips, line pages
- route shapes
- saved items and recents
- the last alerts, marked **stale** with their age

Place search falls back to timetable stops. Journey planning needs the EFA service and says so; no offline router is claimed. Realtime is never shown as live from cache. Without a downloaded timetable, boards use the online EFA departure monitor and say so, and the map shows stops from the EFA coordinate search around the visible area.

## Localization

- UI in **English, German and Italian**, using Android resources only and per-app language selection (Android 13+ system UI; AppCompat storage on older versions).
- Ladin is not a UI language (it was up to 0.1.6-preview.4, with the UI in German); anyone who had chosen it is switched to German. Alert texts in Ladin from the feeds (GTFS-RT `lld`, EFA `ld1/ld2`) are still understood, but shown only as a fallback.
- GTFS stop names and headsigns use German translations for de and the Italian base names otherwise. EFA requests use the app language (StopFinder uses de/it; see API quirks).
- All clock times are shown in Europe/Rome.

## Known API limitations

- **Date format pitfall (fixed).** EFA silently ignores `itdDate` values with a UTC offset. `DateTimeFormatter.BASIC_ISO_DATE` adds one for zoned values, so the app uses `yyyyMMdd`; a unit test covers this.

- `gtfs.api.opendatahub.com` returned **502** for all dataset/realtime paths on 4 Oct 2026. Hence the fallbacks.
- EFA StopFinder ignores `language=en` (it returns "not identified") and matches names per language. The app queries de and it in parallel and merges the results.
- When EFA StopFinder "identifies" a query, it returns only that one place and drops other matches. With the timetable downloaded, the planner's place search adds the timetable stations EFA left out.
- The timetable knows a stop only by its Italian name and STA's German translation, where there is one; EFA also knows other names, such as "Bahnhof Meran Untermais" for "Stazione di Merano Maia Bassa". Every search by name (Plan, map, stop pickers) therefore lists the same stops from one merge of EFA and the timetable; online, a stop EFA found in the app's language shows EFA's name, otherwise the timetable's. A stop board still titles itself with the timetable's name.
- EFA lists one stop under each of its names with the same id ("Fermata autobus a lunga percorrenza Merano Maia Bassa" and "Stazione di Merano Maia Bassa"). The app keeps the name that matches the query best.
- EFA caps the walk to and from stops at 10 minutes unless `trITMOTvalue100` says otherwise, which hides "walk further to a direct bus" connections. The app sends the planner's walking limit (20 minutes by default).
- `routeType=LEASTINTERCHANGE` also returns trips that leave before the requested time (or, arriving by, arrive after it). The app drops them.
- GTFS has no route colours, `feed_info`, transfers or fares. `trip_short_name` (train numbers) and `bikes_allowed` are imported and shown, but were empty in every sampled row (buses only); whether STA fills them for trains is not yet known. Nothing is shown when they are empty. Translations cover German only, and headsigns only partly.
- Realtime coverage is partial: about 57 trip updates and 135 vehicles at a time. Vehicles not matched to a trip show "trip unknown".
- The vehicle feed puts many buses (most city lines, some regional ones) on their line's run scheduled two hours earlier, the UTC offset; their trip updates name the same wrong run. With a downloaded timetable the app moves each such bus, and its updates, to the run of its line and direction due at its stop now.
- Bike and car sharing data for South Tyrol on ODH is sparse or stale, so those layers are off by default. Bolzano's bike sharing stopped updating on 26 Sep 2026 and Merano's on 31 Dec 2025, so while no bike sharing station anywhere in South Tyrol has reported within 7 days, its switch is hidden in the layers sheet and in Settings (checked at most every 6 hours; an offline start keeps it).
- Bike boxes and e-bike sharing are off by default. No bike box site has places with a socket yet, so the split by socket stays hidden; the booking link is not published, so the sheet has none. Most Ecospazio stations report OFFLINE or OUT_OF_SERVICE outside the season and are drawn grey.
- EV charging is off by default too. Operators report a charging point's status only when it changes, so a status counts as current for up to 7 days and is shown with its time, never as live. About 230 stations from one import have no status at all, route220's status is a bare 0 or 1 whose meaning is undocumented (shown as unknown), hydrogen dispensers in the same feed are left out, and no operator publishes prices.
- EFA `infoLinkURL`s point to an internal network address and are not opened.

## Ticketing limitation

There is **no public API** to buy or validate tickets. The app never imitates tickets, QR codes or accounts. The Tickets screen explains this and links to official information. Fares are shown only when the EFA planner reports them, labelled as planner-reported. `TicketingProvider` is the integration point if a legitimate API appears.

## Notification / backend limitation

Background notifications are an **opt-in periodic check (about 30 min, best effort)** using WorkManager, and the settings text says so. True realtime push requires a server; the contract is in **[docs/PUSH_BACKEND.md](PUSH_BACKEND.md)** (`PushBackend` interface, no-op by default). No reference server is included.

## Material 3 Expressive dependency status

- Expressive APIs (`MaterialExpressiveTheme`, `MotionScheme.expressive()`, `LoadingIndicator`/`ContainedLoadingIndicator`, `ToggleButton` connected groups, `SegmentedListItem`, `HorizontalFloatingToolbar`, `LargeFlexibleTopAppBar`, `MaterialShapes`, `LinearWavyProgressIndicator`, expressive `NavigationSuiteScaffold`/`NavigationSuiteItem`) exist only in the **prerelease** line.
- The project pins `androidx.compose.material3:material3:1.5.0-alpha29` and Compose `1.13.0-alpha01`, the version material3 is built against. Stable 1.4.0 lacks these APIs.
- Opt-ins are explicit per file (`@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)`), and most usage is wrapped in `core:designsystem`.
- When animations are disabled system-wide, the theme switches to the standard motion scheme and static progress.

Toolchain: AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, compileSdk 37.1, targetSdk 37, minSdk 26.

## Future improvements

- Offline routing (e.g. RAPTOR over the GTFS DB) and an offline place index.
- The FCM push backend from `PUSH_BACKEND.md`.
- Accessibility pathways/levels if the feed adds them.
- Screenshot tests (Roborazzi) for key screens, and baseline profiles.
- Wear OS tiles for saved stops.
- Better vehicle-to-trip matching when `trip_id` is missing (via block/route + position).
- Replace the EFA departure board with SIRI-ET if published officially.
- Line colour mapping (operator-provided) once available.

## App name and package id

"South Tyrol Transit" and the package id `org.southtyrol.transit` are **placeholders**. The name is in `app_name` in the three `strings.xml` files. The package id is in `app/build.gradle.kts` (`namespace`, `applicationId`) and is also hard-coded in `AppIcons.component` (`ui/AppIcons.kt`), in `MainActivity`'s stop-intent extras and in `android:configure` of `res/xml/departures_widget_info.xml`; change all of them together, or the icon switcher and the widget stop working.

## App icons

The launcher icons come from the PNGs in `icons/` (default: Alpine Route, a vector icon converted by hand from `icons/Alpine Route.svg` rather than generated; users pick one in Settings → Appearance). Adding one takes five steps: put the PNG in `icons/`; run `python tools/generate_icons.py` (needs `pip install pillow numpy`), which fills the black corners, centres the art so the route stays inside the circular mask and writes the adaptive-icon layers and previews; add an `activity-alias` for it to `AndroidManifest.xml`; add an entry to `AppIcon` in `ui/AppIcons.kt`; and add its `icon_<name>` string to `values`, `values-de` and `values-it`. Never rename an existing alias: a user who picked it would be left without a launcher entry. `res/drawable/ic_launcher_monochrome.xml` is the themed-icon silhouette (mountains and route), `ic_launcher_monochrome_route.xml` the route-only one Gradient Path uses, `ic_launcher_monochrome_alpine_route.xml` Alpine Route's own; `MONOCHROME` in the script picks it per icon.
