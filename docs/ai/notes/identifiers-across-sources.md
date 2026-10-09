---
type: Reference
title: Stop and line identifiers across GTFS, EFA and saved items
description: The station key (it:22021:468) is the only join between GTFS and EFA; saved stops and lines are keyed by derived keys, not GTFS primary keys, so changing stationKey, lineKey or TransportMode breaks saved items; the EFA liveRef string format couples the departure monitor to trip live times.
tags: [identifiers, gtfs, efa, saved-items, data-model]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

# Three id systems

| Source | Stop id | Example |
| --- | --- | --- |
| GTFS `stop_id` | platform level | `it:22021:468:1:2805` |
| Station key | first three `:` segments of `parent_station`, else of `stop_id`, `Parent` prefix removed (`GtfsFiles.stationKey` in `GtfsImporter.kt`) | `it:22021:468` |
| EFA `stateless` / `stopID` | numeric | `66000468` |

EFA also sends the station key as `gid`, which the parser stores in `Place.stopGlobalId`; DM departures use
the station-level `gid` and ignore the platform-level `pointGid`. Offline (GTFS) places use the station key
as `Place.id` *and* `stopGlobalId`, and that key is sent to EFA as `name_origin=it:22021:468`. Stop boards
are opened with `stopGlobalId`.

Rules:

- **Never compare a `Place.id` from EFA with one from GTFS** - the same station is `66000468` in one and
  `it:22021:468` in the other. Recent places (keyed by `place.id`) can hold one station twice.
- The `66000000 + n` <-> `it:22021:n` pattern visible in fixtures is not used in code; do not rely on it.
- International trains carry foreign gids in EFA stop sequences (`de:09162:100`); they never match GTFS.
- On EFA boards (`BoardSource.NETWORK`) `Departure.routeId` is EFA's line `stateless`
  (`apb:01B01: :H:26a`, with spaces) and `stopId` is a gid - not GTFS ids. Anything matching on
  `routeId`/`stopId` must handle both spaces (board filters, alert matching).

# Saved items are keyed by derived keys

`SavedRepository` in `TransitRepository.kt`:

- saved stop: `id = stop.id`, the **station key**;
- saved line: `id = line.key` = `GtfsFiles.lineKey` = `"<TransportMode name>:<short name | long name |
  route_id>"`, e.g. `BUS:201` - **not** `route_id`.

So saved lines survive feed swaps while STA keeps the short name. They break if `TransportMode.fromGtfs`
is remapped, an enum constant is renamed, or STA renames a line; saved stops break if STA changes its
stop_id segment scheme. `AlertCheckWorker` matches notifications on the same strings. Changing
`stationKey`, `lineKey` or `TransportMode` names needs a data migration of `user.db`'s saved rows
([schedule database](./schedule-database-lifecycle.md)).

# `liveRef` - the link from a board to EFA trip live times

`EfaXml.departures` builds `stateless|stopID|tripCode|yyyyMMdd|HHmm` (planned time from
`itdDateTimeBaseTimetable` when present); `EfaClient.liveTrip` splits on `|` and requires exactly five
non-blank parts. `LiveOverlay` copies the matched EFA entry's `liveRef` onto the timetable departure, which
is how a GTFS trip gets EFA per-stop times ([live times](./live-times-two-sources.md)). The line
`stateless` contains spaces - never trim or whitespace-split it; the date must stay in the `EFA_DATE`
format (`yyyyMMdd`, no offset). A broken format fails **silently**: `TripRepository.liveTrip` swallows
errors and returns null. Pinned in `ParserTest`.

# What was checked

`stationKey`, `lineKey`, `saveStop`/`saveLine` re-read on 2026-10-09; the EFA parts come from a code read
of `Efa.kt` and its fixtures the same day.
