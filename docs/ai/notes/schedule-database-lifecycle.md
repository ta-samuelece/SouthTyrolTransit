---
type: Finding
title: The schedule and user databases - versioning, swaps, migrations and the importer
description: Importer changes reach existing installs only when GtfsImporter.VERSION is bumped; neither Room database has migrations; the importer writes raw SQL with hard-coded column lists; caches must key on ScheduleStore.version; how stop search is built.
tags: [room, database, gtfs, importer, search, migrations]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
status: stable
---

README section 12 describes the import pipeline. This note records what you must know before changing it.

# Two databases, nothing shared

| Database | File | Holds | On feed swap |
| --- | --- | --- | --- |
| `ScheduleDatabase` | `filesDir/schedule/schedule-<uuid>.db`, pointer file `schedule/active`, journal mode TRUNCATE | the imported feed | replaced wholesale |
| `UserDatabase` | `user.db` | saved items, recents, `cache` (alerts), `notified_alerts` | untouched |

# Importer changes need a version bump to reach existing installs

`ScheduleStore.importFile` skips a feed whose SHA-256 matches the active one **and** whose database was
built by the current `GtfsImporter.VERSION` (meta key `importerVersion`; databases from before the key
read as 0). A change to import logic - search normalisation, shape tolerance, `stationKey`/`lineKey`
derivation, a new meta key - therefore reaches phones only if you **bump `GtfsImporter.VERSION`** in the
same change: the next sync then downloads unconditionally and re-imports the same feed. Without the bump
it waits until STA publishes a new feed (issue #7, fixed 2026-10-09).

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

- `scheduledDepartures` filters by active service in Kotlin, after the SQL query, because up to ~1,000
  services can be active on one day - more than SQLite's 999 bound parameters on minSdk 26. It therefore
  pages through the rows (`LIMIT`/`OFFSET`, ordered by time and trip) until it has `limit` active
  departures, capped at 20,000 rows per service day (issue #10).
- The 1 % broken-reference check covers `stop_times` only; trips with an unknown `route_id` or
  `service_id` silently vanish (the board query joins `routes`). Blank times copy the previous stop's
  departure - they are not interpolated, despite a comment saying so.

# What was checked

Hash skip, the two builders and the version declarations re-read on 2026-10-09; the importer-version
re-import is pinned by `ScheduleTest.sameFeedIsImportedAgainAfterAnImporterChange`; the rest from a code read
of `ScheduleStore.kt`, `GtfsImporter.kt`, `GtfsSchedule.kt`, `Database.kt` and `ScheduleTest` the same day.
