---
type: Finding
title: The schedule and user databases - versioning, swaps, migrations and the importer
description: Importer changes never reach existing installs until STA publishes a feed with a new hash; neither Room database has migrations; the importer writes raw SQL with hard-coded column lists; caches must key on ScheduleStore.version; how stop search is built.
tags: [room, database, gtfs, importer, search, migrations]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

README section 12 describes the import pipeline. This note records what you must know before changing it.

# Two databases, nothing shared

| Database | File | Holds | On feed swap |
| --- | --- | --- | --- |
| `ScheduleDatabase` | `filesDir/schedule/schedule-<uuid>.db`, pointer file `schedule/active`, journal mode TRUNCATE | the imported feed | replaced wholesale |
| `UserDatabase` | `user.db` | saved items, recents, `cache` (alerts), `notified_alerts` | untouched |

# Importer changes do not reach existing installs

`ScheduleStore.importFile` returns early when `loadInfo()?.hash == hash`, and the meta table stores no
importer or format version. So a change to import logic - search normalisation, shape tolerance,
`stationKey`/`lineKey` derivation, a new meta key - has **no effect** on a phone until STA publishes a feed
with a different SHA-256, possibly weeks later. A change that must apply at once needs an importer-version
meta key compared next to the hash.

# No migrations, in either database

Both are `@Database(version = 1, exportSchema = true)`; neither builder (`ScheduleStore.open`,
`AppModule`) adds migrations or `fallbackToDestructiveMigration`. Schemas are exported by KSP to
`core/data/schemas/` - commit the new `N.json` with any schema change.

- **User DB:** a schema change without a hand-written `Migration` crashes on first access and can lose
  saved items. Always write the migration and bump the version.
- **Schedule DB:** opening the old file fails Room's identity check; `GtfsSchedule.isAvailable()` only
  checks that the pointer exists, so every offline query fails (as `DataError.Parse`) until the next
  successful import. `loadInfo()` swallows the failure, so the hash check misses and a re-import does
  happen - but only when the sync worker next runs under its constraints (inferred). Bump the version
  and treat a failed open as "no schedule".

# The importer bypasses Room

`GtfsImporter` writes through `INSERT OR REPLACE INTO <table> (<cols>) VALUES (?)` with hard-coded column
lists into the Room-created file. A new `NOT NULL` entity column without a default breaks every insert
unless the importer is updated in the same change; duplicate ids are silently replaced. `translations.txt`
must be read before `stops.txt`.

# Caches must follow the feed

`ScheduleStore.version` is bumped only by `importFile`; `GtfsSchedule.prepare` keys its calendar, agency
and 10-day service caches on it. Any new cache of schedule data must check `version` too, or it serves
the previous feed after a swap. `ScheduleStore` must stay the only writer (a `@Singleton`): it caches the
pointer file on first read.

# Stop search

`stop_search` (FTS4, `unicode61`) has one row per station key whose text is the distinct
`TextNormalizer.key(...)` forms of every platform name and every translation. Queries use
`GtfsFiles.ftsQuery`: same normaliser, at most 6 tokens, each prefix-matched. Accent folding happens in
Kotlin on both sides, so changing `TextNormalizer.key` needs a re-import (see above). `searchStops` fetches
`limit * 8` platform rows **without** `ORDER BY` and ranks afterwards, so for very common tokens the best
station can be cut before ranking.

# Other limits worth knowing

- `scheduledDepartures` applies its SQL `LIMIT` (`limit * 4`) **before** filtering by active service, so a
  busy station on a day with many inactive services can show fewer departures than asked for.
- The 1 % broken-reference check covers `stop_times` only; trips with an unknown `route_id` or
  `service_id` silently vanish (the board query joins `routes`). Blank times copy the previous stop's
  departure - they are not interpolated, despite a comment saying so.

# What was checked

Hash skip, the two builders and the version declarations re-read on 2026-10-09; the rest from a code read
of `ScheduleStore.kt`, `GtfsImporter.kt`, `GtfsSchedule.kt`, `Database.kt` and `ScheduleTest` the same day.
