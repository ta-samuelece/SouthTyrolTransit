---
type: Reference
title: Following a single ride - the shared slot, RideEngine and when it ends
description: A ride (one timetable run) is followed through the same in-memory ActiveJourney slot, foreground service, notification id and strip as a journey, one at a time; RideEngine loads the run, polls realtime and the planner like the trip screen, RideFollow in core/model turns the merged run into a stage (waiting, moving, at a stop, arrived, cancelled), and following ends itself a minute after arrival or cancellation, leaving a dismissable notice.
tags: [follow, ride, trip, service, notification, realtime]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-29T18:00:00Z"
status: draft
---

Built on 2026-10-29 from the "Follow a Ride" design canvas. Not yet compiled or run: the container had no
Android SDK and could not reach Google's Maven repository, so only `core:model` tests ran (with a standalone
Kotlin compiler, `RideFollowTest` 9 cases plus `JourneyFollowTest` and `RunLocateTest`).

# Decisions (user, 2026-10-29)

- One journey **or** ride at a time. Starting a ride while something is followed asks first ("Follow bus
  201 instead?"); starting a journey from its detail screen still replaces a ride silently.
- Tapping the strip or the notification opens the run's own trip screen (no separate screen); the strip is
  hidden there, as the journey strip is on the Following screen.
- Memory only, like journeys: lost when Android ends the app.
- Buses and trains: without a GPS fix the position is estimated from live times or the timetable, and the
  strip says "estimated".
- No "Get off at" stop yet: following runs to the last stop.

# Who owns what

- `ActiveJourney` holds `followed: StateFlow<Followed?>` (sealed: `FollowSession` or `RideSession`) plus
  the per-kind `session`/`view` and `ride`/`rideView`; `replace()` sets all of them together, so the two
  kinds are mutually exclusive. `FollowJourneyService` collects `followed` and runs `FollowEngine` or
  `RideEngine`; a ride never uses the location service type.
- `RideEngine` loads the run with `TripRepository.trip` (retrying every 2 min while the timetable is not
  there), polls GTFS-RT and the planner's live times every 30 s (looking up the planner reference with
  `findLiveRef` when the ride was started without one, as `TripViewModel` does), merges with
  `TripRepository.merge` and recomputes every 15 s.
- `RideFollow` (core/model): `position` is the trip screen's rule (GPS fix placed with `RunPositions.locate`,
  else `estimate`); `step` gives the stage and the call it is about. A GPS fix still on the way keeps a late
  run going past its arrival time for up to 30 min (`OVERRUN_SECONDS`); `expired` ends following then.
  `RunPosition.atStop` means standing at `nextIndex - 1`.
- Ending: once the stage is ARRIVED or CANCELLED for `ARRIVED_GRACE_SECONDS` (60 s) in a row, the engine
  posts a dismissable notice (`Notifications.ID_FOLLOW_ENDED`, "... No longer followed.") and stops.
  Stop or Undo ends without a notice.
- Words come from `FollowText.ride*`, shared by the notification, the strip (`RideStrip` in
  `JourneyStrip.kt`) and the trip screen's `FollowingRideCard` (`feature/follow/FollowRide.kt`).
  `RideView.live` (Live freshness and a prediction behind the step) is the only rule for drawing live; a
  GPS fix without predictions is not live. A stale fix shows "Last seen near" with the age, the badge grey.
- Entry points: `rememberRideFollower` (replace dialog, notification permission, start) on the trip
  screen (extended FAB, then a snackbar with Undo) and on the map's vehicle sheet. Hidden for a cancelled
  run or one past its last arrival.
- The notification on Android 16+ is a Live Update with one segment in the mode colour and the vehicle as
  tracker, as the journey's (see [following a journey](./follow-journey-live.md) for why there are no points).
