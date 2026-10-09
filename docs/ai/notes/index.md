# Notes

Findings that cost time to learn, and standing facts about the repository. One concept per file; each
entry repeats the note's `description`.

# Open work

* [Known defects](./known-defects.md) - the register of open bugs and documentation errors; GitHub issues are not used for these, so an entry is deleted in the same change that fixes it.

# Data and realtime

* [Stop boards take live times from two sources](./live-times-two-sources.md) - where GTFS-RT has no prediction (often), boards, the map stop sheet and the widget overlay EFA delays matched by line and scheduled minute; trips do the same per stop; GTFS-RT delays only propagate through updates with a stop_sequence. The README's realtime section mentions only GTFS-RT.
* [Stop and line identifiers across GTFS, EFA and saved items](./identifiers-across-sources.md) - the station key (it:22021:468) is the only join between GTFS and EFA; saved stops and lines are keyed by derived keys, not GTFS primary keys, so changing stationKey, lineKey or TransportMode breaks saved items; the EFA liveRef string format couples the departure monitor to trip live times.
* [GTFS time, service days and the Europe/Rome rule in code](./gtfs-time-and-service-days.md) - GtfsTime.instant is the only correct way from a GTFS time and service date to an Instant; Departure.serviceDate has a default that is wrong past midnight; boards look back exactly one service day; two different "exceptions" maps; one known device-zone fallback.
* [The schedule and user databases](./schedule-database-lifecycle.md) - importer changes never reach existing installs until STA publishes a feed with a new hash; neither Room database has migrations; the importer writes raw SQL with hard-coded column lists; caches must key on ScheduleStore.version; how stop search is built.
* [How the timetable download and background sync really behave](./timetable-download-and-sync.md) - app start resets the sync to Wi-Fi only, the "initial" sync re-runs on every cold start, conditional download only works for HTTPS from the same source, the 150 MB HTTPS download shares a 90-second call timeout, a rejected feed is re-downloaded on retry; the TransitHttp retry contract.
* [EFA parsing](./efa-parsing-quirks.md) - which EFA fields count as realtime or cancelled, the different delay windows per parser, why trains are labelled "R", the empty-list-not-error contract, AddInfo filtering, and the limits of the DOCTYPE guard.
* [How service alerts are deduplicated, matched to departures and cached](./alerts-merge-and-matching.md) - GTFS-RT and EFA alerts merge on one identical normalised header plus overlapping periods, ignoring scope across sources; matching is string-exact and stop scope only counts for alerts without line scope; alerts count as live for 15 minutes.
* [Languages across sources](./languages-across-sources.md) - Ladin users see German GTFS names but Italian EFA results; never pass the comma-separated textLanguages() to EFA; localized() prefers a language-neutral text over fallbacks; the places a new UI language must be registered.

# App

* [Adding screens - routes, the Navigator, ViewModel keys and the search pattern](./app-screens-navigation-viewmodels.md) - how a route is declared and registered (screen<T> helper, Navigator method), how a tab is added, why parameterised ViewModels need an argument-derived key, the distinctUntilChanged search pattern from 8fcb0b7, and Hilt worker wiring.
* [App icons and the package name](./app-icons-and-package-name.md) - generate_icons.py (needs Pillow and numpy) only writes images; a new icon also needs a manifest activity-alias, an AppIcon entry and three strings; alias names are permanent; the package name is hard-coded in the icon switcher, the widget and MainActivity.
* [In-app updater internals](./in-app-updater-internals.md) - hourly in-memory check throttle, the lenient certificate comparison, the three coupled updates/ paths, how version strings compare, and why the update dialog and the Settings section can disagree.
* [Map and design-system conventions](./map-and-design-system.md) - map content is one immutable value re-pushed in full and marker ids must be unique across stops, vehicles and POIs; the dark style falls back to the light override; attribution and font are hard-coded to OpenFreeMap; feed route colours are already used; reduced motion is read once.

# Build, test and release

* [There is no CI](./no-ci-every-check-is-local.md) - a GitHub Actions workflow existed for a few minutes on 2026-10-05 and was deleted; it built a debug APK with Java 17, ran no tests, and would have published debug-signed releases on every v* tag.
* [Test suite layout, and the network tests that are off by default](./test-suite-and-opt-in-live-tests.md) - four kinds of tests across three modules; no screen or ViewModel is tested despite the README; the tests that download the real STA feed or hit the FTP server only run with -PliveTests=true or -PgtfsReal; TranslationsTest fails on any missing or stale en/de/it string.
* [Release build, R8 keep rules and build configuration](./release-build-r8-and-build-config.md) - R8 keeps only GTFS-RT protobuf and MapLibre explicitly and relies on library consumer rules for the rest, so only assembleRelease exercises it; local.properties beats environment variables; no Kotlin Android plugin under AGP 9; Gson arrives transitively.
