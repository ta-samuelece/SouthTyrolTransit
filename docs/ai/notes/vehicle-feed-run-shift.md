---
type: Finding
title: STA's vehicle feed puts many buses on the run scheduled two hours earlier
description: Most city lines and some regional ones report, in GTFS-RT vehicle positions and trip updates, the trip_id of their line's run due at the vehicle's stop one UTC offset earlier; VehicleRunResolver moves each such vehicle, and its trip updates, to the run due there now before the snapshot is published.
tags: [realtime, gtfs-rt, vehicles, map, trip]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-21T20:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-21T19:40:00Z"
status: stable
sources:
  - id: resolver
    resource: ../../../core/data/src/main/kotlin/org/southtyrol/transit/data/Realtime.kt
    title: VehicleRunResolver and its hook in RealtimeRepository.refresh
  - id: rule
    resource: ../../../core/model/src/main/kotlin/org/southtyrol/transit/model/Realtime.kt
    title: VehicleRuns
---

# What happens

The STA GTFS-RT vehicle-positions feed gives many buses the `trip_id` of the run of their line that was
due at their current stop **two hours earlier** - the UTC offset in summer, so presumably one hour in
winter. Position, `stop_id` and timestamp are current; only the run is wrong. Trip updates carry the same
wrong `trip_id`, with delays that fit the bus actually running. The user saw it as "Show trip" on a map
bus opening a run a couple of hours old.

Measured on 2026-10-21 at 21:37 (live feed against the static timetable of the same day): 27 of 52
vehicles reported a run 90-140 minutes behind the clock at their stop - lines 110, 201, 10B, 9, 8, 6,
150, 170, 204, 210, 211, 212, 237, 240, 263, 271 and one 460 run. The other 25 were within minutes. For
every shifted vehicle, exactly one run of the same route and direction was due at its stop within
20 minutes of now; looking for a run exactly +2 h instead missed about half of them. One check against
the journey planner: bus 201 at Funivia del Renon reported the 19:34 run, while EFA had its 20:35
Merano run arriving there at 21:29.

# What the app does

`VehicleRunResolver` (core/data) runs inside `RealtimeRepository.refresh`, before the snapshot is
published, so every consumer - the map, the trip screen's vehicle, journey and followed-journey runs,
board predictions - sees corrected ids. It is wired in `AppModule`; tests construct
`RealtimeRepository` without one.

- For each vehicle with a trip and a stop: load the reported run; if its call at that stop is more than
  `VehicleRuns.MAX_OFFSET` (60 min) from now, take the same route's calls at that stop within
  `VehicleRuns.WINDOW` (20 min), closest first, departures or else arrivals (a run's last stop has no
  departure), and keep the first whose trip has the reported run's direction.
- The answer is cached per vehicle, reported trip and date; at most 20 vehicles are resolved per feed
  update, so a first map view shows the rest on the next polls.
- Trip updates for a moved run are renamed to the vehicle's run, using the latest vehicle feed's moves,
  and placed first: an update already filed under the actual run belongs to a bus hours later.
- Without a downloaded timetable nothing is changed.

A bus genuinely more than an hour late would be moved to whatever run of its line is due at its stop
now, if there is one. Not seen; accepted.

# What was checked

`VehicleRunResolverTest` (moved run, kept run, line and direction filter, trip updates follow, no
timetable). The measurement above with throwaway scripts against the live feeds and the static zip;
not yet confirmed on a device.
