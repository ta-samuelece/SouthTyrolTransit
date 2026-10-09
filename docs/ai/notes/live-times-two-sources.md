---
type: Finding
title: Stop boards take live times from two sources - GTFS-RT first, then the EFA departure monitor
description: Where GTFS-RT has no prediction (often), boards, the map stop sheet and the widget overlay EFA delays matched by line and scheduled minute; trips do the same per stop. The README's realtime section mentions only GTFS-RT.
tags: [realtime, gtfs-rt, efa, departures, freshness]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
status: stable
sources:
  - id: overlay-code
    resource: ../../../core/model/src/main/kotlin/org/southtyrol/transit/model/Realtime.kt
    title: LiveOverlay and LiveTripMerge
  - id: board-merge
    resource: ../../../core/data/src/main/kotlin/org/southtyrol/transit/data/TransitRepository.kt
    title: DepartureRepository.liveOverlay and merge
  - id: commits
    resource: https://github.com/ta-samuelece/SouthTyrolTransit/commit/75bdf86
    title: 75bdf86 (boards, 2026-10-06) and cd00c0b (trip timeline, 2026-10-07)
---

# What happens

The GTFS-RT trip-updates feed is often empty or thin (README section 16: about 57 trip updates at a time)
while the STA journey planner (EFA) still reports delays. So a timetable board gets live times in two
layers, applied in `DepartureRepository.merge`:[^board-merge]

1. **GTFS-RT** via `RealtimeMerge.departure`, when the trip-updates feed counts as live.
2. **EFA overlay** via `LiveOverlay.apply`, for departures still without a prediction. The overlay comes
   from `DepartureRepository.liveOverlay`, which asks the EFA departure monitor for 80 entries and
   returns an empty list on any error - it is a best-effort extra, never a reason to fail the board.

`LiveOverlay` matching:[^overlay-code]

- same line, compared by `lineKey` (case, spacing and a leading `Bus`/`Tram`/`Zug`/`Treno` ignored), and
  scheduled time within **60 s**; a similar destination breaks ties;
- fallback for trains, which are named differently in the two sources (`REG` in GTFS, `R 16719` in EFA):
  same mode, same minute, similar destination;
- each EFA entry is used at most once; a cancelled EFA entry marks the departure cancelled.

Callers: `StopViewModel`, the map's stop sheet (`MapScreen`) and `DeparturesWidget` - the latter two only
when the board comes from the downloaded timetable (`BoardSource.SCHEDULE`).[^commits]

Trips use the sibling `LiveTripMerge`: the EFA stop sequence for the run, matched per call by station and
scheduled time within **120 s**, so loops visiting a station twice still match the right call.

# Rules that follow from it

- The overlay counts as fresh for **3 minutes** after it was fetched (`overlayFresh` in `merge`).
- A board says "Live" only when at least one departure actually carries live data. A fresh but empty
  GTFS-RT feed must not make a pure timetable look live.
- The board's "updated ... ago" uses whichever source is newer.
- Boards built from the EFA board itself (`BoardSource.NETWORK`, no timetable downloaded) get no overlay -
  they already are EFA data.

# What was checked

Read the code paths named above on 2026-10-09 (`Realtime.kt`, `TransitRepository.kt`, the three call
sites). Not checked against a live feed.

[^board-merge]: DepartureRepository.liveOverlay and merge
[^overlay-code]: LiveOverlay and LiveTripMerge
[^commits]: 75bdf86 and cd00c0b
