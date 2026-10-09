---
type: Reference
title: Adding screens - routes, the Navigator, ViewModel keys and the search pattern
description: How a route is declared and registered (screen<T> helper, Navigator method), how a tab is added, why parameterised ViewModels need an argument-derived key, the distinctUntilChanged search pattern from 8fcb0b7, and Hilt worker wiring.
tags: [compose, navigation, viewmodel, hilt, search, workmanager]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
status: stable
---

# A new screen

- Routes are `@Serializable` objects or data classes in `app/src/main/kotlin/org/southtyrol/transit/ui/Routes.kt`,
  with string or primitive arguments only. Complex arguments are encoded to a string
  (`ResultsRoute.request` is `RequestCodec` JSON).
- Add a method on `Navigator`; screens never touch the `NavController`, they receive `navigator`.
- Register it in `TransitApp.kt` with the private `screen<T> { }` helper, **not** plain `composable` - the
  helper adds the predictive-back shrink, rounded corners and scrim (`BACK_MS`, `BackShrink`).
- Transitions are global on the `NavHost`: slide plus fade for sub-views, fade for tab switches
  (`isTabSwitch()`) or when `LocalReducedMotion` is set.
- A sub-route stays under the tab that opened it (`Navigator.sync` changes `tab` only for `TopLevel`
  destinations). `Navigator.select` pops to the user's chosen start tab, not always Plan.

A new **tab** additionally needs a `TopLevel` entry, a `StartTab` value in core:data (exhaustive `when` in
`TopLevel.of`), `startTabLabel` in `SettingsScreens.kt`, and nav strings in all three languages.

# ViewModel arguments - and the key

Two conventions: `hiltViewModel()` + `SavedStateHandle.toRoute<...>()` (`ResultsViewModel`,
`JourneyDetailViewModel`), and assisted factories keyed by the argument
(`hiltViewModel<StopViewModel, StopViewModel.Factory>(key = stationKey) { it.create(...) }` for stop,
trip and line). **The key is required**: `DeparturesScreen` creates several `StopViewModel`s in one
back-stack entry (two-pane), and stop/trip/line navigation uses `launchSingleTop`. Without an
argument-derived key a VM shows data for the wrong stop.

Journey detail after process death re-plans from the encoded request and matches `Journey.id`
(`depEpoch:arrEpoch:lines`); a "leave now" request re-plans from the new now, so it usually comes back
`DetailState.Missing`. `JourneyRepository` keeps results in memory only.

# Search screens - the pattern from 8fcb0b7

A search VM keeps query, loading and results in one `MutableStateFlow`. Searching on that flow directly
re-runs the search on its own results (the reload loop fixed in `8fcb0b7`). Always narrow to the query
first:

```kotlin
_state.map { it.query.trim() }.distinctUntilChanged().debounce(200).collectLatest { query -> ... }
```

Used by `DeparturesViewModel`, `LinesViewModel`, `StopSearchViewModel`, `LineSearchViewModel`;
`PlannerViewModel` does the same with `query to field`. Set `loading` only when the trimmed query
changed, so typing a space does not flash a spinner.

# Polling and workers

- `PollWhileVisible(period, key)` restarts when `key` changes. `StopViewModel.refresh` reloads the
  timetable part only when it is older than a minute, so a 30 s poll refreshes realtime every tick and the
  timetable every other.
- The manifest removes `WorkManagerInitializer`; WorkManager relies on `TransitApplication` being a
  `Configuration.Provider` with `HiltWorkerFactory`. Every new worker needs `@HiltWorker` and
  `@AssistedInject` with `@Assisted` context and params.
- Widget state is JSON (`WidgetDeparture`) in Glance preferences, decoded with `runCatching`: renaming a
  field silently empties placed widgets until their next refresh.

# What was checked

Code read of `ui/`, `feature/*`, `widget/` and `work/` on 2026-10-09; the search pattern re-checked in
five call sites.
