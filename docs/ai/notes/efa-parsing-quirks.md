---
type: Finding
title: EFA parsing - what the parsers accept, drop and map, and their error contract
description: Which EFA fields count as realtime or cancelled, the different delay windows per parser, why trains are labelled "R", the empty-list-not-error contract, AddInfo filtering, and how the DOCTYPE guard works.
tags: [efa, parsing, xml, realtime, alerts, security]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-30T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-30T12:00:00Z"
  what: stop-finder aliases, trITMOTvalue100 cap and LEASTINTERCHANGE past trips against the live EFA API
status: stable
---

`docs/API_DISCOVERY.md` section 3 describes the EFA endpoints. This is how `EfaXml` and `EfaClient` in
`core/data/.../Efa.kt` treat them; most of it is pinned in `ParserTest` against `docs/fixtures/`.

# Departure monitor (DM)

- **Train labels are the EFA `trainType`** (`R`, `RV`, `RJ`, `EC`), not `symbol` (`REG`, which GTFS uses)
  and not the long `R 16142` (used only for journey `Leg.lineName`). `ParserTest` asserts
  `assertEquals("R", train.line)`. This mismatch is why the live overlay needs its train fallback.
- Realtime only when `itdServingLine@realtime == "1"`. `realtimeTripStatus="MONITORED"` with
  `realtime="0"` is common and counts as scheduled.
- Cancelled when a realtime status contains `CANCEL`, or `itdNoTrain@delay == -9999`.
- Predicted time is `itdRTDateTime`, else `delay` accepted only in `0..600` minutes - early running is
  dropped unless `itdRTDateTime` is present.
- Delay windows differ per parser: DM `0..600`, journey intermediate stops `-120..600` (realtime legs only),
  stop sequence `-60..600` (honouring `arrValid`/`depValid`). A `-9999` cancellation in a stop sequence
  falls outside the range, so `LiveTripMerge` cannot show cancelled calls. Unifying the windows changes
  pinned tests.
- `dateTime()` returns null for EFA placeholder dates (year <= 0, negative hour/minute).
- StopIDs starting `9999999` are coordinates, not stops.

# Error contract

- `itdMessage` codes are never read. A trip request throws `EfaFailure("origin"|"destination")` only when
  that place is `notidentified` or `empty`; everything else without routes - including -8020 with
  identified places, and `via` problems - returns an **empty list**. A UI wanting "no connection found"
  handles the empty list.
- `EfaCodes.NO_ROUTE` exists but is never thrown; `SAME_PLACE` is thrown by `JourneyRepository.plan`.
- The code strings (`"origin"`, `"destination"`, `"same"`) are duplicated as literals in
  `core/designsystem/.../Format.kt` - change both together.
- StopFinder states other than `identified`/`list` return an empty list. `identified` carries **one**
  element only: EFA picks the best match and drops the rest (`efa_stopfinder_list.xml` is such an
  answer despite its name). So `PlacesRepository.search` appends timetable stations EFA did not return,
  matched on station key = EFA `gid`, whenever the timetable is downloaded. Whether a request parameter
  makes EFA always answer with a list (`odvSugMacro`?) is untested.
- **One stop, several names, one id.** A list can name the same stop twice with the same `stateless`
  and `gid` ("Maia Bassa": the long-distance bus alias at 397 listed first, "Stazione di Merano Maia
  Bassa" at 401 second - `efa_stopfinder_aliases.xml`). `rankedPlaces` sorts by `matchQuality` before
  de-duplicating by id, so the better-matching name survives. Until 2026-10-30 it kept the first listed,
  and because the station's `gid` then counted as returned, the timetable merge dropped the station too:
  that was why map-search stops were missing from the planner.

# Trip request

- `trITMOT=100` + `trITMOTvalue100` is the longest walk to and from stops in minutes; without it EFA caps
  it at 10, which hides "walk 18-24 min to a direct bus" trips. The planner sends
  `SearchOptions.maxWalkMinutes` (10/20/30, default 20). At 30, `LEASTINTERCHANGE` readily swaps a short
  bus ride for a 28-minute walk, which is why it is a choice rather than a fixed high value.
- `LEASTINTERCHANGE` answers a departure search with trips that already left (asked 22:30, got 20:56).
  `EfaClient.inWindow` drops departures over a minute before the requested time and, for arrive-by,
  arrivals over a minute after it. The default preference is `FEWEST_CHANGES`, so this matters.

# AddInfo (alerts)

Items with `publish="0"`, `deactivated="true"` or an end date in the past are dropped at parse time.
Header is `infoText/subtitle`, else `infoLinkText` (skipping the literal `Information`); body is `subject`
plus HTML-stripped `content` (`subject` is not stripped). `stopBlocking`/`lineBlocking` map to
`NO_SERVICE`, everything else `OTHER_EFFECT` - so a new AddInfo type stays non-disruptive until mapped.
`lineNames` come from `<line number>` (`7A`, `280`). The fixture carries all languages per item although a
language is requested (inferred: the cache key need not be language-specific).

# The DOCTYPE guard

`EfaXml.root` rejects a `<!DOCTYPE` anywhere in the **prolog** (`EfaXml.prolog`: everything before the
root element - a DTD cannot appear later), then parses with `isExpandEntityReferences = false`, secure
processing and `disallow-doctype-decl`, each in a `runCatching` because Android's parsers vary. Until
2026-10-09 only the first 4096 bytes were scanned (issue #9); `rejectsDoctype` now also covers a DTD
behind a 10 KB comment. The parser's 15 MB cap sits behind a
30 MB download cap, so a 15-30 MB response downloads fully before failing as `DataError.Parse`.

# What was checked

The `trainType` label and its test assertion re-read on 2026-10-09; the rest from a code read of `Efa.kt`,
`ParserTest` and the fixtures the same day, not re-verified line by line. The stop-name aliases, the
footpath cap and the `LEASTINTERCHANGE` past trips were reproduced against the live API on 2026-10-30
(Via Piero Agostini 2, Bolzano -> Via Guglielmo Marconi 58, Laives, at 14:00 and 22:30).
