---
type: Finding
title: How service alerts are deduplicated, matched to departures and cached
description: GTFS-RT and EFA alerts merge on one identical normalised header plus overlapping periods, ignoring scope across sources; matching is string-exact and stop scope only counts for alerts without line scope; alerts count as live for 15 minutes.
tags: [alerts, gtfs-rt, efa, dedupe, cache]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

# Dedupe (`AlertDeduplicator`, `core/model/.../Text.kt`)

Two alerts merge when their ids are equal, **or** when all of these hold:

- one whole header is identical after `TextNormalizer.key` (keys longer than 3 characters; not word
  overlap);
- their validity periods overlap;
- `sameScope`: either has no scope, or they share a route, stop or line name, **or they come from
  different sources** (`a.source != b.source`) - because GTFS-RT `routes` (route_id) and EFA `lineNames`
  (`<line number>`) are never comparable.

Each group is compared against its first member only. Input is sorted by `AlertSource.ordinal`
(GTFS-RT before EFA), so GTFS-RT's id, headers and urls win; scopes are unioned. Consequence: two
unrelated disruptions from different sources with the same generic header ("Umleitung") and overlapping
periods collapse into one alert carrying both scopes. Reordering `AlertSource` changes which source wins.

# Matching (`AlertMatcher.affectsDeparture`)

```kotlin
d.routeId in alert.routes || d.tripId in alert.trips || (alert.lineNames.isNotEmpty() && d.line in alert.lineNames) ||
  (alert.routes.isEmpty() && alert.lineNames.isEmpty() && alert.trips.isEmpty() && (d.stopId in alert.stops || GtfsFiles.stationKey(d.stopId, "") in alert.stops))
```

- A line-scoped alert flags that line at **every** stop, even if it also lists stops.
- `d.line` is compared raw - not with `LiveOverlay.lineKey` - so `R`/`REG` or whitespace differences miss.
- On EFA boards `routeId` is EFA's `stateless`, so GTFS-RT route-scoped alerts never match there
  ([identifiers](./identifiers-across-sources.md)).
- GTFS-RT alert stops store both the platform id and its station key; EFA stops are station-level only.
  Keep both forms, or station views lose matches.

# Freshness, errors and cache

- `AlertsState.freshness` is LIVE for **15 minutes** after the newer of the two fetches (in no other doc).
- `AlertsState.error` is set only when **both** sources failed.
- GTFS-RT alerts are cached as raw protobuf (`gtfsrt:service-alerts`), EFA alerts as JSON
  (`efa:addinfo`, `Codec.alerts`, an unknown `source` defaults to EFA). Old cache rows are restored on
  start, so a format change needs tolerant parsing.
- `RealtimeRepository.refresh` holds one `Mutex` across all feed fetches and stamps `lastAttempt` on
  failures too: a failed feed is throttled like a successful one, and a slow alerts fetch blocks
  trip-update refreshes. A new `RealtimeFeed` needs both a `minInterval` and an `apply` branch.

# What was checked

`sameScope` and the dedupe condition re-read on 2026-10-09; matching, freshness and cache from a code read
of `Text.kt`, `Realtime.kt` (core:model and core:data) and `TransitRepository.kt` the same day.
