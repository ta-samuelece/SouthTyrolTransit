---
type: Finding
title: How the timetable download and background sync really behave
description: App start resets the sync to Wi-Fi only, the "initial" sync re-runs on every cold start, conditional download only works for HTTPS from the same source, the 150 MB HTTPS download shares a 90-second call timeout, a rejected feed is re-downloaded on retry; the TransitHttp retry contract.
tags: [gtfs, sync, workmanager, network, okhttp, ftp]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

README section 12 describes the intended strategy. As of 2026-10-09 the code differs in these ways.

# Scheduling (`work/Workers.kt`, `TransitApplication.kt`)

- **App start resets "download on mobile data".** `TransitApplication.onCreate` calls
  `scheduleTimetableRefresh()` with the default `allowMetered = false`, and the periodic work uses
  `ExistingPeriodicWorkPolicy.UPDATE`, which replaces the stored constraints. Only
  `SettingsViewModel.metered` passes the user's value, so the setting lasts until the next cold start. Any
  periodic work registered in `onCreate` with `UPDATE` has the same trap.
- **The "initial" one-off sync re-runs on every cold start.** It is enqueued with `ExistingWorkPolicy.KEEP`,
  which only skips while the previous run is still pending or running. So the real cadence is about once
  per cold start on Wi-Fi, not weekly (inferred from WorkManager's semantics).
- `ScheduleSyncWorker` returns `Result.retry()` for any `DataException` while `runAttemptCount < 3`, so a
  feed rejected by validation is downloaded again (~150 MB) up to three more times.
- Its foreground notification is indeterminate and never updated; progress is visible only in the UI
  through `ScheduleStore.status`.

# Download (`ScheduleStore.fetch`)

- **Conditional requests only for HTTPS, and only from the same source**: `If-None-Match` /
  `If-Modified-Since` are sent only when `previous?.source == source.name`. The FTP fallback always
  downloads in full; it stores `MDTM` but never compares it. Unchanged feeds are caught by the SHA-256
  check only after the whole download.
- **The HTTPS download shares the app-wide OkHttp client**, whose `callTimeout(90, SECONDS)` covers reading
  the body too (OkHttp semantics). On slow connections the ~150 MB download fails mid-body - body reads are
  not retried - and falls through to FTP, which has no total limit. A dedicated client
  (`newBuilder().callTimeout(0, ...)`) would fix it.
- `toDataError` maps every `IOException` to `DataError.Offline`, including a corrupt zip, "Download too
  large" and "Incomplete FTP download", so users see "offline" for those. `refresh` reports the *first*
  source's error even when the FTP fallback then failed differently.
- FTP is plaintext; `network_security_config.xml`'s "HTTPS only" does not cover it (raw sockets). The
  `ftpFallback` setting is the only gate.

# `TransitHttp` retry contract (pinned by `NetworkTest`)

Three attempts in total. Network `IOException`s retried after 800 ms then 1600 ms; `UnknownHostException`
never. HTTP 429 and 5xx retried, other 4xx throw `HttpFailure` immediately. `Retry-After` over 20 s
throws at once (`DataError.Throttled`), shorter is waited out (at least 800 ms). `304` is returned as a
success - callers check `response.code`. Only time-to-headers is retried, never body reads. OkHttp's own
`retryOnConnectionFailure` sits underneath. The OkHttp disk cache is 20 MB in `cacheDir/http`.

# What was checked

The `onCreate` call, the work policies, `runAttemptCount`, the same-source header condition and
`callTimeout` re-read on 2026-10-09; the rest from a code read of `ScheduleStore.kt`, `Network.kt`,
`Workers.kt` and `NetworkTest` the same day. The 90-second failure was not reproduced on a device.
