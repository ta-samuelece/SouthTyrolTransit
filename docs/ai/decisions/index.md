# Decision records

One file per non-obvious decision: the context that forced it, what was decided, and what it costs.
Add the next one as `NNNN-short-title.md` with `type: Decision` (see [conventions.md](../conventions.md))
and list it below.

Good candidates already visible in the README, not yet written up: the Material 3 alpha pin, the
MapLibre OpenGL ES build, and features as packages rather than modules.

# Records

* [0001 Clean-architecture layering in the existing modules, without a rebuild](./0001-clean-architecture-layering.md) - core/model is the pure domain, screens and ViewModels reach data only through core/data repositories (never EfaClient, ScheduleStore or DAOs), dependencies point one way; UseCase-per-call, Koin, a presentation/domain module split and blanket Result wrapping were considered and rejected.
* [0002 Update sheet with per-release cards, then a separate Play Store build](./0002-update-sheet-and-play-build.md) - Release cards, icons and translations travel in a separate whats-new.json because the installed version draws the sheet and old versions render the release body as text; categories are just the notes' headings; a later Play build is a real github/play variant split with the same package name and signing key, showing the notes after an update instead of before it.
