---
type: Reference
title: Trip schedules - storage, the Europe/Rome window rule and what is still deferred
description: A trip schedule is a saved item of kind TRIP_SCHEDULE with a JSON payload (no Room migration, carried by the backup); its next window is today's until today's end, else the first chosen day up to a week ahead, always built in TransitZone; Next trips pages the planner at most 4 times per window and filters by scheduled departure or arrival; nothing runs by itself yet - alarms, boarding detection and moving on to the next trip are later slices.
tags: [schedules, saved-items, planner, time, follow]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-26T12:00:00Z"
status: draft
---

Issue #86, first slice (2026-11-26): schedules are defined, stored, listed and opened by hand. Not yet
confirmed on a device.

# Storage

- `SavedKind.TRIP_SCHEDULE` rows in the `saved` table of `user.db`, id `schedule:<uuid>`, label the name,
  subtitle `"<from> → <to>"`. `SavedRepository.saveSchedule` gives a new one the next `position` and keeps
  `position` and `created` when replacing, so the DAO's `kind, position` order is creation order.
- The payload is `Codec.schedule`: name, `from`/`to` as place objects, `days` as ISO numbers 1-7,
  `start`/`end` as `"HH:mm"`, `arriveBy`, `enabled`. The id is the row's, not in the payload. A bad payload
  decodes to null and `SavedItem.tripSchedule` is null.
- No `SavedRow` or `UserDatabase` change, so no migration. An older app version skips the rows, because
  `SavedRepository.saved` drops kinds it cannot parse. The rows travel with the whole-file backup of
  `user.db` ([backup](./backup-and-restore.md)).

# The window rule

`TripSchedule` (`core/model/src/main/kotlin/org/southtyrol/transit/model/TripSchedule.kt`) is pure Kotlin.
`nextWindow(now)` takes today (in `TransitZone`) when it is a chosen day and `now` is before today's end
(a window under way counts as today's); otherwise the first chosen day after today, up to 7 days ahead.
Start and end are `date.atTime(...).atZone(TransitZone)`, never the device zone; on the spring DST day a
window across 02:00 is an hour shorter. A window never crosses midnight: validation requires start < end,
plus a non-blank name, at least one day and From != To (same `id` and `name`, the planner's rule).

# Next trips

`NextTripsViewModel` plans through `JourneyRepository.plan` with only wheelchair and walking speed from the
settings. Leaving between: from the window start, then from the last departure + 60 s until one leaves
after the end. Arriving between: arrive by the window end, then from the first arrival − 60 s (still
arrive-by) until one arrives before the start. **At most 4 planner calls** per window; `NotFound` ends the
paging. `connectionsIn` keeps the first leg's scheduled departure (or the last leg's scheduled arrival)
inside [start, end], dedupes by id and sorts by departure. Each journey remembers the encoded request it
came from, which the journey detail and `FollowSession` need. The screen re-plans every 2 minutes while
visible (`PollWhileVisible`). Because it goes through `plan`, each refresh also touches recent journeys
and places.

# Deferred to later slices

Starting by itself at the window (alarms, the "Alarms & reminders" permission, re-arming after reboot,
zone change or update), a persistent schedule notification, moving on to the next trip after a missed
one, boarding detection and "Not on this bus", "Pause for today", overlap rules, public holidays,
per-schedule routing options, and showing schedules on the Saved screen, widgets or the map. The enabled
switch is only stored and shown.
