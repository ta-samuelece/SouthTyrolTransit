---
type: Reference
title: Train numbers, bikes on board and distances along a run
description: trip_short_name, bikes_allowed and shape_dist_traveled are imported (schedule database version 2) and shown on boards, the trip screen and journey legs only when the feed fills them; distances are taken as metres; "next stop in" and the progress bar need a fresh GPS fix; "travelling with a bike" filters results by the timetable runs the legs were matched to, not by the planner.
tags: [gtfs, importer, trip, journey, bikes, distance, ui]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-24T16:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-24T16:00:00Z"
status: draft
---

Three GTFS fields the app ignored until 2026-10-24. The design is the canvas "Ride distance, train
numbers and bikes" (Claude design artifact); this note records what the code does and what is unknown.

# Where the data comes from

| GTFS field | Stored as | Model |
| --- | --- | --- |
| `trips.trip_short_name` | `trips.shortName` | `Trip.shortName`, `Departure.tripName` |
| `trips.bikes_allowed` | `trips.bikes` (0/1/2) | `Bikes` on `Trip` and `Departure` |
| `stop_times.shape_dist_traveled` | `stop_times.distance`, whole metres, null when blank | `TripStop.distanceMeters`, `Departure.remainingMeters`, `TripDetail.lengthMeters` |

The spec leaves the distance unit to the feed; the STA sample rows are in metres (0.00, 577.15, 932.81
along line 111), so the importer takes them as metres. If STA ever switches to kilometres, every
distance in the app is off by 1000 - check a sampled `stop_times.txt` row first when distances look wrong.

Fill rates are **not known**: the only sampled rows (`docs/fixtures/gtfs-inventory.json`, buses) have the
distance filled and the other two empty. Everything is built so an empty field shows nothing: no train
number, no bike symbol, no warning except in the bike search below.

# Rules the UI keeps

- `Bikes.UNKNOWN` (empty or 0) is never drawn. "No bikes" always carries its words; the crossed-out icon
  (`BikeIcon`, `feature/common/Common.kt`) never stands alone.
- `RouteDistance.of` (`core/model/.../Progress.kt`) gives travelled / to go / to the next stop only for a
  GPS fix between two calls, and the trip screen only passes it while the fix is `Freshness.LIVE`: an
  estimate or a stale fix would claim a precision the app does not have. The stretch to the next stop is
  measured on the shape and clamped to the timetable's gap, so it never contradicts the stop list.
- Distances per stop on the trip screen are relative to the run's first stop.
- The stop board's next-departure card does **not** show the distance to the run's last stop: it was
  removed on 2026-10-24 at the user's request. `Departure.remainingMeters` is still imported but no
  screen reads it.

# Travelling with a bike

`SearchOptions.withBike` is **not sent to EFA** (whether the planner honours a bike option was not
checked). `ResultsViewModel` matches every leg of every result to its timetable run with
`TripRepository.forJourney` (see [journey leg matching](./journey-leg-live-vehicles.md)) before showing
the list, and `JourneyBikes.excluded` hides a journey only when a run is known to take no bikes; the
list says how many were hidden and can show them. A leg with no match or no rule gets a warning in the
journey detail. Without a downloaded timetable nothing matches, so nothing is hidden and every transit
leg warns.

# What was checked

`RouteDistanceTest` and `LegDistanceAndBikesTest` (core:model) pass, run with a standalone `kotlinc` on
2026-10-24 because the machine could not reach the Android SDK or AGP. The importer test
`ScheduleTest.trainNumbersBikesAndDistancesAreImported`, the migration, the screens and lint were **not
run** there: build and run `:core:data:testDebugUnitTest`, `:app:testDebugUnitTest` and `lintDebug`
before relying on them, and check a device with an installed version-1 timetable to see the migration.
