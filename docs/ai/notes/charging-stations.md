---
type: Reference
title: EV charging stations
description: Three ODH requests (stations, charging points, their status) joined by the point's pcode; operators report a status only when it changes, so it counts as current for 7 days and is shown with its time, never as live; four status vocabularies including route220's undocumented 0/1; hydrogen and planned stations are dropped; code-like station names fall back to the address.
tags: [mobility, odh, map, charging, freshness]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-24T22:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-24T22:30:00Z"
status: draft
---

The fourth mobility layer, added 2026-10-24 and off by default. The design is the canvas "EV charger
map entries" (Claude design artifact); the endpoints and coverage are in `docs/API_DISCOVERY.md`,
section 4. This note records what is not obvious from the code.

# Shape of the code

- `MobilityKind.CHARGING` goes through the same pipeline as parking and sharing: `OdhChargingSource`
  (`core/data/.../ChargingSource.kt`) implements `MobilityDataSource`, so `MobilityRepository` and the
  map treat it like the other layers. `OdhMobilitySource` throws for `CHARGING`; the app module builds the
  source list explicitly, not from `MobilityKind.entries`.
- The details ride on `MobilityPoint.charging` (`ChargingDetails`); everything that interprets them
  (status words, connector types, the fast rule, grouping, titles) is pure Kotlin in
  `core/model/.../Charging.kt`. `MobilityPoint.available` is the free count, `capacity` the number of
  points.
- The sheet is `ChargingSheet.kt` in `feature/map`; the map screen picks it when `charging` is set.

# The join

A station's points are the `EChargingPlug` records whose `pcode` equals the station's `scode`; each
point's status is the `/latest` record with the point's own `scode`. The status request is best effort:
when it fails the stations still show, with no count. A station with no listed point gets as many
undescribed points as its `smetadata.capacity` says.

# Freshness: change-only reports

Operators publish a point's status when it changes, not periodically, so a quiet charger's status can be
days old and still true. The 3-hour mobility rule would hide most of them, so chargers have their own:
`FreshnessPolicy.CHARGING_MAX_AGE` (7 days, chosen by the user on 2026-10-24). A point whose status is
older counts as `UNKNOWN`; the station's `updatedAt` is its newest current report, and its `freshness` is
`LIVE` while that is within 7 days. The sheet never shows the "Live" pill for a charger: it shows "Status
as of <time>" (date and time when not today).

# Things that surprise

- Four status vocabularies (OCPI, OICP, route220's bare `0`/`1`, Neogy's `REMOVED`); see
  `Charging.status`. The meaning of route220's numbers is not documented, so they count as unknown - if
  it is ever found, map them there.
- IIT's hydrogen dispenser is in the charging feed ("700 bar small vehicles"); a station whose every
  outlet is hydrogen is dropped. Stations with `state` PLANNED are dropped too.
- Station names are often operator codes. `Charging.isCode` (no lower-case letter, plus an underscore,
  asterisk or digit) decides; the address replaces them, and the sheet says "Charging station" when there
  is no address either (`MobilityPoint.name` is then empty).
- Several Route220 stations share one coordinate (one per point), so their tiles overlap.
- An out-of-service station (every point out of order) is drawn grey through `MapMarker.color`, without
  a count; a fast one (a DC point of `Charging.FAST_KW` or more) gets `MapMarker.badge`.
