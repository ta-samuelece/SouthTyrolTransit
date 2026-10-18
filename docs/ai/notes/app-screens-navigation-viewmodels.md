---
type: Reference
title: Adding screens - routes, the Navigator, ViewModel keys and the search pattern
description: How the first-run screens are gated before the app, how a route is declared and registered (screen<T> helper, Navigator method), how a tab is added, how the map's search hides the bottom bar, why parameterised ViewModels need an argument-derived key, the distinctUntilChanged search pattern from 8fcb0b7, and Hilt worker wiring.
tags: [compose, navigation, viewmodel, hilt, search, workmanager, onboarding]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-17T14:30:00Z"
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
`TopLevel.of`), `startTabLabel` in `SettingsScreens.kt` and in the onboarding screen (with its `startTabs`
list), and nav strings in all three languages. There are
three tabs (Plan, Map, Alerts). Settings stopped being a tab on 2026-10-13: it is an ordinary sub-route
(`Navigator.settings()`), reached only from the app-mark menu of the map's search bar. The Stops tab was
merged into the map on 2026-10-17: its search, saved and nearby stops are `MapSearchViewModel` and
`MapSearchView` in `feature/search/MapSearch.kt`, shown over the map. `StartTab.SETTINGS` and
`StartTab.DEPARTURES` stay in the enum only so a stored choice still parses: `UserSettings` reads
DEPARTURES back as MAP, `TopLevel.of` maps SETTINGS to Plan, and the start-tab choices hide both.

The map's search covers the screen inside `MapScreen` (not a route), so a stop board or the Lines screen
opened from it comes back to it. While it is open, `MapScreen` sets `Navigator.searchOpen` and
`TransitApp` hides the bottom bar through the `NavigationSuiteScaffold` state, but only while the current
destination is `MapRoute`; a `DisposableEffect` clears the flag when the map leaves the screen. Closing the
search clears its text; the field takes focus once per opening (`takeFocus`), not on coming back from a
board.

# First-run screens

`feature/onboarding/OnboardingScreen.kt` is not a route: `MainActivity` shows `OnboardingScreen()` in
place of `TransitApp` while `UserSettings.onboardingDone` is false, and `TransitApp` once it turns true, so
the start tab passed in is the one just chosen there. `OnboardingGate.resolve` decides it at start: a
fresh install (`firstInstallTime == lastUpdateTime`) gets the screens; an updated install predates them and
is marked done on the spot instead. Skip jumps to the summary and Start sets the flag.

- Each choice is written to the settings as it is made, through the same calls as Settings (alert checks,
  `scheduleTimetableRefresh` with `initialSync` while there is no timetable), so a user who leaves halfway
  keeps what they picked.
- Picking a language calls `AppLanguage.select`, which recreates the activity: the current step is kept
  in `rememberSaveable` for that reason, and picking the phone's own language goes back to SYSTEM.
- `onboardingDone` is a settings key, so it is backed up: a restored phone skips the screens (see
  [backup and restore](./backup-and-restore.md)).

# ViewModel arguments - and the key

Two conventions: `hiltViewModel()` + `SavedStateHandle.toRoute<...>()` (`ResultsViewModel`,
`JourneyDetailViewModel`), and assisted factories keyed by the argument
(`hiltViewModel<StopViewModel, StopViewModel.Factory>(key = stationKey) { it.create(...) }` for stop,
trip and line). **The key is required**: stop/trip/line navigation uses `launchSingleTop`, so one
back-stack entry can be reused for another argument. Without an argument-derived key a VM shows data for
the wrong stop.

Journey detail after process death re-plans from the encoded request and matches `Journey.id`
(`depEpoch:arrEpoch:lines`); a "leave now" request re-plans from the new now, so it usually comes back
`DetailState.Missing`. `JourneyRepository` keeps results in memory only.

`FollowRoute` (the journey being followed) takes no argument: its state is the in-memory
`ActiveJourney`, so after process death it shows "No journey is being followed". The notification opens it
through `MainActivity.openFollowIntent`, handled in `TransitApp` like the widget's stop intent (see
[following a journey](./follow-journey-live.md)).

# Search screens - the pattern from 8fcb0b7

A search VM keeps query, loading and results in one `MutableStateFlow`. Searching on that flow directly
re-runs the search on its own results (the reload loop fixed in `8fcb0b7`). Always narrow to the query
first:

```kotlin
_state.map { it.query.trim() }.distinctUntilChanged().debounce(200).collectLatest { query -> ... }
```

Used by `MapSearchViewModel` (stops and lines from one query), `LinesViewModel`, `StopSearchViewModel`, `LineSearchViewModel`;
`PlannerViewModel` does the same with `query to field`. Set `loading` only when the trimmed query
changed, so typing a space does not flash a spinner.

# Polling and workers

- `PollWhileVisible(period, key)` restarts when `key` changes. `StopViewModel.refresh` reloads the
  timetable part only when it is older than a minute, so a 30 s poll refreshes realtime every tick and the
  timetable every other. Loads can overlap (a poll and a "later" tap); `refresh` applies a result only
  if the board's window (`start`, `end`, `arrivals`) is still the one it loaded for (issue #19).
- The manifest removes `WorkManagerInitializer`; WorkManager relies on `TransitApplication` being a
  `Configuration.Provider` with `HiltWorkerFactory`. Every new worker needs `@HiltWorker` and
  `@AssistedInject` with `@Assisted` context and params.
- Widget state is JSON (`WidgetDeparture`) in Glance preferences, decoded with `runCatching`: renaming a
  field silently empties placed widgets until their next refresh. The status line comes from
  `WidgetState.status`: a failed refresh before any successful one shows `widget_load_failed`, not
  "Loading..." (issue #16, pinned by `WidgetStatusTest`).

# What was checked

Code read of `ui/`, `feature/*`, `widget/` and `work/` on 2026-10-09; the search pattern re-checked in
five call sites.
