---
type: Finding
title: How the timetable download and background sync really behave
description: Periodic work registered at app start must carry the saved mobile-data choice (UPDATE replaces constraints); the one-off initial sync only runs without a timetable; conditional download only works for HTTPS from the same source; the timetable download uses its own client without a call timeout; unusable feeds are not retried; which failures count as offline; the TransitHttp retry contract.
tags: [gtfs, sync, workmanager, network, okhttp, ftp]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T23:00:00Z"
status: stable
---

README section 12 describes the strategy for users. This note records the mechanics and the traps.

# Scheduling (`work/Workers.kt`, `TransitApplication.kt`)

- **Periodic work registered at app start must use the saved settings.** The weekly sync is enqueued with
  `ExistingPeriodicWorkPolicy.UPDATE`, which replaces its stored constraints. `TransitApplication.onCreate`
  therefore reads `scheduleOnMetered` (a suspend call, on `Dispatchers.IO`) before registering it; until
  2026-10-09 it passed the default and reset the setting to Wi-Fi only on every cold start (issue #3).
  Any new periodic work registered in `onCreate` has the same trap.
- **The one-off "initial" sync is only enqueued when no timetable exists** (`initialSync =
  scheduleStore.activeName() == null`). `ExistingWorkPolicy.KEEP` only skips while a previous run is
  pending or running, so enqueuing it unconditionally re-ran it on every cold start (issue #6). A pending
  initial job keeps the constraints it was queued with: switching on mobile data does not change it.
- `ScheduleSyncWorker` retries a `DataException` up to three times, **except** `DataError.Parse`: a feed
  that downloaded but failed validation (or is corrupt or oversize) would fail again, and each retry is
  ~150 MB (issue #5).
- Its foreground notification is indeterminate and never updated; progress is visible only in the UI
  through `ScheduleStore.status`.

# Download (`ScheduleStore.fetch`)

- **Conditional requests only for HTTPS, and only from the same source**: `If-None-Match` /
  `If-Modified-Since` are sent only when `previous?.source == source.name` and the database was built by
  the current `GtfsImporter.VERSION`. The FTP fallback always downloads in full; it stores `MDTM` but
  never compares it. Unchanged feeds are caught by the SHA-256 check only after the whole download.
- **The timetable download has its own client** (`TransitHttp.forLargeDownloads`): the app-wide client's
  `callTimeout(90, SECONDS)` also covers reading the body, so a slow ~150 MB download was cut off and fell
  through to FTP (issue #4). The copy has no call timeout; the 40-second read timeout still catches
  stalls. Any other large body must use it too.
- **Which failures count as "offline"** (`toDataError`): only connectivity errors and other I/O from the
  network stack. `BadDataException` (oversize response or download) and `ZipException` map to
  `DataError.Parse`; `TransferFailedException` (FTP protocol errors, an incomplete FTP download, a failed
  activation) maps to `DataError.Unknown`. Throw one of those for a new non-network failure - a plain
  `IOException` is reported as "you are offline" (issue #11). `refresh` reports the *first* source's
  error even when the FTP fallback then failed differently.
- FTP is plaintext; `network_security_config.xml`'s "HTTPS only" does not cover it (raw sockets). The
  `ftpFallback` setting is the only gate.

# `TransitHttp` retry contract (pinned by `NetworkTest`)

Three attempts in total. Network `IOException`s retried after 800 ms then 1600 ms; `UnknownHostException`
never. HTTP 429 and 5xx retried, other 4xx throw `HttpFailure` immediately. `Retry-After` over 20 s
throws at once (`DataError.Throttled`), shorter is waited out (at least 800 ms). `304` is returned as a
success - callers check `response.code`. Only time-to-headers is retried, never body reads. OkHttp's own
`retryOnConnectionFailure` sits underneath. The OkHttp disk cache is 20 MB in `cacheDir/http`.

# What was checked

On 2026-10-09, after the fixes: an emulator check that a fresh install schedules the weekly and the
initial sync on unmetered network, and that after switching on mobile data and restarting the app the
weekly job allows metered networks; `NetworkTest` pins the error mapping and the download client's
missing call timeout. The slow-download failure itself was never reproduced on a device.
