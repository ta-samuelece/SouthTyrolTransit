---
type: Finding
title: Known defects - open bugs found in the code and not yet fixed
description: The register of open bugs and documentation errors, raised with the user at every session start (keep here or move to GitHub issues); an entry is deleted in the same change that fixes or moves it.
tags: [defects, bugs, backlog]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T16:00:00Z"
status: stable
---

Bugs are tracked **here** unless the user decides to move them to GitHub issues: at the start of every
session the hook lists these entries and the agent asks (CLAUDE.md rule 12). One entry per defect,
numbered, title in bold - the hook reads that shape. What goes wrong, where, and the likely fix. When a
fix lands, or an entry moves to a GitHub issue, delete it in the same change - this is a list of what is
open, not a history (git has that). When you find a new defect while working, add it here and tell the
user. Background for each lives in the linked note.

# Behaviour

1. **App start resets "download timetable on mobile data" to Wi-Fi only.** `TransitApplication.onCreate`
   calls `scheduleTimetableRefresh()` with its default `allowMetered = false`, and the periodic work uses
   `ExistingPeriodicWorkPolicy.UPDATE`. Fix: pass `settings.current().scheduleOnMetered` (a suspend read,
   so not synchronously in `onCreate`), or register with `KEEP`.
   [sync](./timetable-download-and-sync.md)
2. **The ~150 MB HTTPS timetable download is cut off after 90 seconds** on slow connections: it shares the
   app-wide OkHttp client's `callTimeout(90, SECONDS)`, which covers reading the body, and body reads are
   not retried; it then falls back to FTP. Fix: a dedicated client via `newBuilder().callTimeout(0, ...)`.
   Not reproduced on a device. [sync](./timetable-download-and-sync.md)
3. **A feed rejected by validation is downloaded again up to three more times.** `ScheduleSyncWorker`
   returns `Result.retry()` for every `DataException`. Fix: fail without retry on `DataError.Parse` /
   `GtfsValidationException`. [sync](./timetable-download-and-sync.md)
4. **The "initial" timetable sync runs on every cold start** (`ExistingWorkPolicy.KEEP` only skips while
   the previous run is pending), not once after install. Inferred from WorkManager semantics. Fix: only
   enqueue it when no schedule exists. [sync](./timetable-download-and-sync.md)
5. **Importer fixes never reach existing installs** until STA publishes a feed with a new SHA-256:
   `importFile` skips on an unchanged hash and stores no importer version. Fix: an importer-version meta
   key compared next to the hash. [schedule database](./schedule-database-lifecycle.md)
6. **The trip screen falls back to the device time zone.** `TripViewModel.load` uses `LocalDate.now()`
   when the route's date does not parse. Fix: `LocalDate.now(TransitZone)`.
   [GTFS time](./gtfs-time-and-service-days.md)
7. **The EFA DOCTYPE guard scans only the first 4096 bytes** of a response. Fix: also set the
   `disallow-doctype-decl` parser feature (inside a `runCatching`, Android parsers vary).
   [EFA parsing](./efa-parsing-quirks.md)
8. **Busy stations can show too few departures**: `scheduledDepartures` applies its SQL `LIMIT` before
   filtering by active service. Fix: filter services in SQL, or raise the limit. Effect inferred.
   [schedule database](./schedule-database-lifecycle.md)
9. **Non-network failures show as "offline"**: `toDataError` maps every `IOException` - corrupt zip,
   "Download too large", "Incomplete FTP download", oversize responses - to `DataError.Offline`.
   [sync](./timetable-download-and-sync.md)

# Repository and documentation

10. **A Python bytecode file is committed**: `tools/__pycache__/generate_icons.cpython-311.pyc` (since
    `75bdf86`), and `.gitignore` has no `__pycache__/` rule. Fix: `git rm --cached` it and add the rule.
11. **README section 1 understates the icon and package-rename steps.** `generate_icons.py` only writes
    images (and needs Pillow and numpy); renaming the package touches more than two files.
    [app icons](./app-icons-and-package-name.md)
12. **README section 11 claims app UI tests that do not exist** (journey search and selection, stop
    departures, saving a stop). [test suite](./test-suite-and-opt-in-live-tests.md)
13. **README section 12 claims a progress notification and conditional download for every source.** The
    sync notification is indeterminate, and FTP downloads are never conditional.
    [sync](./timetable-download-and-sync.md)
