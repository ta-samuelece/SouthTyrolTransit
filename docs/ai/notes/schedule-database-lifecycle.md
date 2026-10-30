---
type: Finding
title: The schedule and user databases - versioning, swaps, migrations and the importer
description: Importer changes reach existing installs only when GtfsImporter.VERSION is bumped; the schedule database has one migration (1 to 2, added columns) and the user database none; the importer writes raw SQL with hard-coded column lists; caches must key on ScheduleStore.version; queries go through ScheduleStore.withDatabase, which keeps a replaced database open until its users finish; how stop search is built.
tags: [room, database, gtfs, importer, search, migrations]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-30T17:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-11T12:00:00Z"
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
it waits until STA publishes a new feed (issue #7, fixed 2026-10-09). The bump also makes the
start-up timetable prompt (`ui/TimetablePrompt.kt`, via `ScheduleStore.updateAvailable`) offer every
user a ~150 MB download on their next app open, so bump only when the re-import matters.

# Migrations: one in the schedule database, none in the user database

Schemas are exported by KSP to `core/data/schemas/` - commit the new `N.json` with any schema change
(the build writes it; `2.json` of the schedule database was not generated on the machine that wrote
the 1-to-2 migration, so the first build afterwards must add it). Neither builder adds
`fallbackToDestructiveMigration`.

- **User DB** (`version = 1`, `AppModule`): no migrations. A schema change without a hand-written
  `Migration` crashes on first access and can lose saved items. Always write the migration and bump the
  version. The migration must also handle files restored from the phone's backup, which can come from any
  older version ([backup and restore](./backup-and-restore.md)).
- **Schedule DB** (`version = 2`, `ScheduleStore.open`): `ScheduleDatabase.MIGRATION_1_2` adds
  `trips.shortName`, `trips.bikes` and `stop_times.distance` with `ALTER TABLE ... ADD COLUMN`, so an
  installed timetable keeps working, with those columns empty, until the importer-version bump (to 2)
  re-imports it. Entity columns added this way must declare the same `@ColumnInfo(defaultValue)` as the
  `ALTER TABLE` (or be nullable without one), or Room's schema check rejects the migrated file. Without a
  migration, opening the old file fails Room's identity check; `GtfsSchedule.isAvailable()` only checks
  that the pointer exists, so every offline query fails (as `DataError.Parse`) until the next successful
  import. `ScheduleRepository.need` does read the file (`loadInfo`), so the start-up timetable prompt
  treats an unreadable file as missing and offers a download - but only if the user accepts it, or the
  sync worker next runs under its constraints. So a schema change needs a migration that keeps old
  files readable, plus an importer-version bump to fill them.

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

# Queries hold the database they run on

Every schedule query goes through `ScheduleStore.withDatabase`, which counts the users of each open
database. Activating a new feed retires the old one: it is closed and its file deleted only when the last
running query on it returns (until 2026-10-11 it was closed at once, failing those queries - issue #18).
Never keep a `ScheduleDatabase` or DAO beyond the `withDatabase` block that produced it.

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
re-import is pinned by `ScheduleTest.sameFeedIsImportedAgainAfterAnImporterChange`, the deferred close
by `activatingANewScheduleKeepsTheOldOneOpenForRunningQueries` (2026-10-11); the rest from a code read
of `ScheduleStore.kt`, `GtfsImporter.kt`, `GtfsSchedule.kt`, `Database.kt` and `ScheduleTest` the same day.
