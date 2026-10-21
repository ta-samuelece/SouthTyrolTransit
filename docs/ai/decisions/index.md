# Decision records

One file per non-obvious decision: the context that forced it, what was decided, and what it costs.
Add the next one as `NNNN-short-title.md` with `type: Decision` (see [conventions.md](../conventions.md))
and list it below.

Good candidates already visible in the README, not yet written up: the Material 3 alpha pin, the
MapLibre OpenGL ES build, and features as packages rather than modules.

# Records

* [0001 Clean-architecture layering in the existing modules, without a rebuild](./0001-clean-architecture-layering.md) - core/model is the pure domain, screens and ViewModels reach data only through core/data repositories (never EfaClient, ScheduleStore or DAOs), dependencies point one way; UseCase-per-call, Koin, a presentation/domain module split and blanket Result wrapping were considered and rejected.
