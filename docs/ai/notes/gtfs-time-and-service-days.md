---
type: Reference
title: GTFS time, service days and the Europe/Rome rule in code
description: GtfsTime.instant is the only correct way from a GTFS time and service date to an Instant; Departure.serviceDate has a default that is wrong past midnight; boards look back exactly one service day; two different "exceptions" maps.
tags: [time, gtfs, dst, timezone, calendar]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
status: stable
---

# The one conversion

`TransitZone = ZoneId.of("Europe/Rome")` is declared in `core/model/.../Transit.kt` (not `Time.kt`).

`GtfsTime.instant(date, seconds)` in `core/model/.../Time.kt` is the single source of truth:

```kotlin
date.atTime(12, 0).atZone(zone).toInstant().minusSeconds(43200).plusSeconds(seconds.toLong())
```

That is the GTFS definition ("noon minus 12 h"). Never write `date.atStartOfDay(TransitZone)
.plusSeconds(...)` - it is an hour off on DST days for times before the switch. `TransitTest` pins it: on
the spring-forward day GTFS `00:00:00` is 23:00 of the previous local day, on fall-back day 01:00;
`08:00:00` is 08:00 on both. The inverse for query windows is `secondsSinceOrigin`. Every `GtfsSchedule`
call site goes through `GtfsTime`.

# Traps

- **`Departure.serviceDate` defaults to `scheduled.atZone(TransitZone).toLocalDate()`.** For a GTFS
  `25:10` departure that is the *next* calendar day, not its service day. `Departure.key`
  (`tripId|serviceDate|stopId|sequence`) and the board's `distinctBy { it.key }` depend on it, and trip
  links open that day. Any code building a GTFS `Departure` must pass `serviceDate` explicitly, as
  `GtfsSchedule` does. The default is only right for EFA departures.
- **Boards look back exactly one service day** (`scheduledDepartures`, `variantTrips` start at
  `toLocalDate().minusDays(1)`), while `GtfsTime.seconds` accepts hours up to 240. Stop times of 48:00+
  parse but never show on a board.
- **Two different "exceptions" maps.** `ServiceCalendar.activeServices` takes only *that date's*
  `calendar_dates` as `serviceId -> added`; `CalendarRule.active(date, exceptions)` takes
  `date -> Boolean`. Passing a feed-wide map to the first silently adds or removes services.
- **Material DatePicker millis are UTC by contract**: `Pickers.kt` and `PlannerScreen.kt` convert them
  with `ZoneOffset.UTC` and then combine with `TransitZone`. That is correct - do not "fix" it.
- "Today" is always `LocalDate.now(TransitZone)`. The last exception, `TripViewModel.load`'s fallback for
  an unparsable route date, was fixed on 2026-10-09 (issue #8).

# What was checked

`GtfsTime.instant`, the `serviceDate` default and the `LocalDate.now` calls re-read on 2026-10-09; the
rest from a code read of `Time.kt`, `Transit.kt`, `GtfsSchedule.kt` and `TransitTest` the same day.
