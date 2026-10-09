---
type: Finding
title: EFA parsing - what the parsers accept, drop and map, and their error contract
description: Which EFA fields count as realtime or cancelled, the different delay windows per parser, why trains are labelled "R", the empty-list-not-error contract, AddInfo filtering, and how the DOCTYPE guard works.
tags: [efa, parsing, xml, realtime, alerts, security]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
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
- StopFinder states other than `identified`/`list` return an empty list.

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
`ParserTest` and the fixtures the same day, not re-verified line by line.
