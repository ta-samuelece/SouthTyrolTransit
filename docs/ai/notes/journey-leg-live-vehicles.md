---
type: Reference
title: Live vehicles in the journey view - how a planner leg finds its run
description: Journey-planner legs carry no trip id, so each transit leg is matched to a timetable run at its boarding station (line and minute, train fallback) and then treated like the trip screen; works only with a downloaded timetable and only until the vehicle leaves the boarding stop.
tags: [journey, realtime, vehicles, efa, gtfs, map]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-31T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T18:00:00Z"
status: stable
---

The journey detail view shows, for each transit leg, where its vehicle is now and the route it still has
to cover before the user's boarding stop: on the map (vehicle marker and a dashed line) and, since
2026-10-31, on the leg card as a row above the boarding stop ("Bus 211 is 3 stops away", "Live · left
Lana, Busbahnhof 11:39") that opens the run's earlier stops on a dashed rail (see "Earlier stops on the
leg card" below). An earlier, always-visible "N stops before yours" row was removed on 2026-10-13 as
noise; the user asked for this collapsible one instead, designed on the "Earlier stops on a plan" canvas.

# From planner leg to run

EFA journey legs (`Leg` in `core/model/.../Transit.kt`) have no trip id, `liveRef` or route id
([identifiers](./identifiers-across-sources.md)). `TripRepository.forLeg` therefore:

1. asks the downloaded timetable for departures at `leg.from.stopGlobalId` (the station key) within
   +-2 minutes of the planned departure;
2. picks one with `LegMatch.departure` (`core/model/.../Approach.kt`): same line by `LiveOverlay.lineKey`
   and scheduled time within 60 s, a similar destination breaking ties; for trains (`R` from EFA vs `REG`
   in GTFS) same mode, minute and destination - the same rules as the
   [board overlay](./live-times-two-sources.md);
3. loads that run with `schedule.trip(tripId, serviceDate)`.

No timetable, a walking leg or no match gives null - never an error, the leg simply shows no vehicle.

# From run to approach

`JourneyLiveViewModel` (in `JourneyViewModels.kt`, assisted factory keyed `"live:" + journey.id`, so the
two-pane results view gets one per selected journey) matches all legs with `TripRepository.forJourney`
(also used by a followed journey, see [following a journey](./follow-journey-live.md)), merges each run with the realtime snapshot through
`TripRepository.merge`, exactly like the trip screen, and polls trip updates and vehicle positions every
20 s while visible. The screen then calls, every 10 s:

- `Approaches.boardingIndex` - the call at the boarding station closest in time to the planned departure
  (loops visiting a station twice pick the right call);
- `Approaches.withBoardingDelay` - applies the planner's delay at the boarding stop to earlier calls that
  have no GTFS-RT prediction, so the estimated position agrees with the boarding time shown;
- `Approaches.of` - the run's fresh GPS fix placed by `RunPositions.locate` (by geometry, a fix at a stop
  counts as standing there), else `RunPositions.estimate`; the path follows the shape from the vehicle to
  the boarding stop, or straight through the remaining stops without a shape.

The map re-fits once when a leg's vehicle first appears - an approaching vehicle is by definition outside
the journey's own bounds, so with the initial fit alone it was usually off-screen. Whether a leg was
matched to a run is no longer visible on its card: check for the vehicle marker on the map, or debug
`JourneyLiveViewModel.legRuns`.

`Approaches.of` returns null before the run starts without a GPS fix, and once the vehicle has left the boarding stop
(the user is then on board; the trip screen covers that). While the vehicle waits at the boarding stop it
reports `atBoarding`.

# Earlier stops on the leg card

`Approaches.beforeBoarding` gives a `RunBeforeBoarding` (the run with the boarding delay applied, the
boarding index, the approach, whether the run has started); `TransitLeg` passes it to `LegStops`, which
draws `EarlierStops` (`feature/common/StopTimeline.kt`) above the boarding row and dashes the rail up to
it. The row says, in order of precedence: at your stop, on its way to your stop (next call), "N stops
away" ("about" when estimated or the fix is stale, with a grey `history` chip), "Starts at <first stop>"
before the run, or for trains (`placeVehicle` false: GTFS-RT has no train positions) the stop count with
"Trains report no position". Opened, it lists the calls from one before the vehicle (`firstShown`; the
last four when not placed) with a "Show N earlier stops, from <first stop>" button for the rest. It is
gone once the vehicle has left the boarding stop, and absent without a matched run or when the leg
boards at the run's first stop.

# While riding

Once a leg is under way, the map splits its line at the vehicle (the run's GPS fix placed by
`RunPositions.locate` when matched, else
estimated from the leg's own times via `RouteProgress.legStops`) and shows the vehicle there; see the
progress convention in [map and design system](./map-and-design-system.md).

# What was checked

`ApproachTest` (line and train matching, loops, GPS vs estimate, before the run, at and after the
boarding stop, no shape, delay shift; since 2026-10-31 also the earlier stops: placement, first stop,
after departure, not started, trains, `firstShown`) and the full unit-test, lint and debug-build run on
2026-10-09; the earlier-stops row passed unit tests and lint on 2026-10-31.
`LegMatchLiveTest` (core:data, opt-in: `-PliveTests=true -PgtfsReal=<zip>`) plans real journeys with the
live planner and reports how many legs match a timetable run; it has not been run yet (2026-10-09: the
machine it was written on cannot reach the STA hosts). Not yet confirmed on a device.
