---
type: Decision
title: Clean-architecture layering in the existing modules, without a rebuild
description: core/model is the pure domain, screens and ViewModels reach data only through core/data repositories (never EfaClient, ScheduleStore or DAOs), dependencies point one way; UseCase-per-call, Koin, a presentation/domain module split and blanket Result wrapping were considered and rejected.
tags: [architecture, repository, viewmodel, hilt, modules]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-21T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-21T12:00:00Z"
sources:
  - resource: "commit f1fba62"
  - resource: "app/src/main/kotlin/org/southtyrol/transit/di/AppModule.kt"
  - resource: "core/data/src/main/kotlin/org/southtyrol/transit/data/TransitRepository.kt"
status: stable
---

# Context

The app was checked against Android clean-architecture guidance on 2026-10-21. The module graph already
matched it: `core/model` is pure Kotlin/JVM with the domain models, the data-source interfaces
(`Interfaces.kt`) and the shared rules (`Realtime.kt`); `app` depends on `core/data`,
`core/designsystem` and `core/map`, which all depend on `core/model`, with no cycles. The gap was five
screens taking `EfaClient` or `ScheduleStore` directly, two of them with copies of the same
timetable-else-EFA stop search. Commit `f1fba62` moved them behind `StopSearchRepository` and
`ScheduleRepository`.

# Decision

Every change keeps these rules:

- **`core/model` is the domain.** No Android dependency. Logic that more than one screen needs (merging,
  freshness, matching) goes here, not into a ViewModel.
- **Screens and ViewModels use repositories only.** They never take `EfaClient`, `ScheduleStore`, a DAO,
  a Room database or an HTTP client. A screen that needs new data gets a repository method, or a small
  new repository provided in `AppModule`.
- **Repositories depend on the `core/model` data-source interfaces** where one exists, so they can be
  tested with fakes or recorded payloads.
- **Dependencies point one way:** `app` to `core/data`, `core/designsystem`, `core/map` to `core/model`.
- **New repositories go in their own file** rather than growing `TransitRepository.kt`.

Not adopted, because for an app this size they cost more than they give: a UseCase class per operation,
Koin (Hilt stays), separate `domain`/`presentation` modules, and wrapping every call in `Result` (the
code has `DataException`/`DataError` and freshness states).

# Consequences

- A change that needs to break a rule says so in its commit and in the reply to the user.
- Background workers in `work/Workers.kt` use `ScheduleStore` and `UserDao` directly: they own the
  timetable download and the alert checks, and are not screens.
- Known exceptions still open: `StopViewModel` and `feature/common/Pickers.kt` (`SavedStopsFlow`,
  `LineSearchViewModel`) take `TransitScheduleDataSource`, a domain interface but not a repository.
  Move them behind a repository when those files are next changed.

# What was checked

On 2026-10-21: the module dependencies in each `build.gradle.kts`, the absence of Android imports in
`core/model`, and the imports of `app/src/main/kotlin` for raw clients, DAOs and data-source interfaces;
the result is the exception list above.
