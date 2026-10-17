---
type: Reference
title: Map and design-system conventions - MapContent, styles, attribution, colours, reduced motion
description: Map content is one immutable value re-pushed in full and marker ids must be unique across stops, vehicles and POIs; without a timetable the map's stops come from the EFA coordinate search; maps inside pages use ExpandableMapPage and enlarge where their small card sits; the one convention for showing progress along a run; the dark style falls back to the light override; attribution and font are hard-coded to OpenFreeMap; feed route colours are already used; the colour scheme (own palette by default, optional wallpaper colours and pure black); which Material 3 Expressive component to use for a choice, a hero, a grouped list and data freshness; the map's locate button is a FAB menu opened by a long press; reduced motion is read once.
tags: [map, maplibre, design-system, colors, accessibility]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-17T14:30:00Z"
status: stable
---

# Map (`core/map`)

- Features use only the `TransitMap` wrapper (`MapLibreTransitMap` is `internal`); the wrapper also draws
  the credit line.
- Content is one immutable `MapContent`; on every change all five GeoJSON sources are re-set. Click
  lookup is `(content.stops + content.vehicles + content.pois).associateBy { it.id }` - **ids must be
  unique across all three lists**. `MapMarker.color` is the stroke for stops and the fill for vehicles and
  journey endpoints; mobility points always use their kind's colour.
- **Marker shapes say the kind**: stops are circle layers; mobility points (parking, bike and car
  sharing) are rounded-square images with a Material icon and, from zoom 16, a pill with
  `MapMarker.label` (the live count); vehicles are images of a mode-icon circle plus the line number in a
  tag of the mode's badge shape, with a separate rotated arrow image. Below zoom 13 vehicles are plain
  dots and mobility points are hidden. A heading between 20 and 160 degrees moves the tag to the left so
  the arrow stays clear; a stale vehicle is grey with the history icon and no arrow. STA's vehicle
  feed sends **no bearing** (none of 135 vehicles in `docs/fixtures/vehicles.json`), so
  `VehicleHeadings` infers it: the target is the GTFS-RT `stop_id` (or the next call when the vehicle is
  STOPPED_AT it or within 25 m), and the heading is the direction of the trip's **route shape** where
  the vehicle is, 30 m ahead, searched only between the previous call and the target so loops do not
  confuse it; straight at the stop only without a shape. `MapRepository.withHeadings` feeds the map
  tab and the line page (needs the offline timetable; trip calls and shapes are cached, shapes once per
  `shapeId`, at most 20 new trips loaded per update so a first view is not held up); the trip map uses
  the trip it already shows, and the journey map the leg or approach path. The images are drawn
  in `MarkerImages.kt` on demand, keyed by everything they show, and re-added after every style load -
  each is centred on the coordinate, so a new image needs no anchor offset.
- A `CameraRequest` is applied only when it differs from the last one; bump its `key` to re-apply the same
  request. `interactive` is applied only when the map is created.
- Clustering: stops up to zoom 13 (radius 42), vehicles up to zoom 10 (radius 36).
- **Styles**: `MainActivity` uses `MAP_STYLE_DARK`, else **`MAP_STYLE_LIGHT`**, else the OpenFreeMap dark
  default - so overriding only `map.styleUrl` gives a light map in dark mode. The OpenFreeMap defaults are
  repeated in `LocalMapStyle` in `TransitMap.kt`; keep both in sync.
- **Attribution** `© OpenStreetMap · OpenFreeMap` is hard-coded in `MapAttribution` (`TransitMap.kt`;
  the SDK's own attribution and logo are off), and all symbol layers use the font `Noto Sans Regular`. Switching tile provider means updating the
  credit (ODbL) and checking the new style serves that font, or labels may not render (inferred).

- **Stops without a timetable**: `MapRepository.stops` reads `stopsIn` from the timetable when one is
  installed, otherwise the EFA coordinate search (`EfaClient.nearbyStops`, `max = 400`) around the
  viewport centre, radius = half the diagonal capped at 3 km, filtered to the box. Online stops use the
  EFA global id as `id` and `stationKey`, the same key as timetable stations, so saved-stop highlighting
  and the stop page work for both. The map screen and the map picker both go through it.
- **Maps inside pages** (trip, journey, line, stop) use `ExpandableMapPage` + `CompactMapCard`
  (`app/.../feature/common/ExpandableMap.kt`): a static card in the list, enlarged to 60% of the height
  outside the list so its gestures never fight the list's scrolling; back shrinks it. `placement` puts
  the enlarged map where the small card is - `BOTTOM` on the stop page, whose map is the last item, `TOP`
  elsewhere. A new page with a map should use these rather than an interactive `TransitMap` in a list.
  The enlarge button sits in the top end corner, where MapLibre puts its compass once the map is
  rotated, so the card passes `TransitMap(compassTopMargin = 60.dp)` to keep the compass below it.
  Any edge-to-edge `TransitMap` must pass a margin too, or the compass lands under the status bar: the
  Map tab passes the status-bar inset plus 12 dp, or plus 76 dp to clear a search bar at the top.
- **Lines**: `MapPolyline` has `opacity` and `dashed`. Dashed lines live in their own layer
  (`line-dasharray` cannot be data-driven on Android) - before 2026-10-09 `dashed` was written to the
  feature but never rendered, so walking legs drew solid.

# Progress along a run - one convention everywhere

Every view that shows a run marks what is already behind: the travelled part of the line and passed stops
are drawn at `Progress.PASSED_ALPHA` (0.35, in `core/designsystem/.../Tokens.kt`), the rest at full
strength. A stop is passed once its (predicted) departure is not after now (`RouteProgress.nextIndex`) -
except where the run has a GPS fix: `RunPositions.locate` (`core/model/.../Realtime.kt`) places the fix on
the route by geometry (the stop it stands within 40 m of, or the stretch it is on; the times only pick the
pass on a loop), and that position decides passed and next on the trip timeline and map, the map's
vehicle sheet ("Next stop") and the journey view. Time-based placement put a late run without
predictions at the wrong stop, or nowhere once its scheduled end had passed.

| View | Travelled part | Passed stops |
| --- | --- | --- |
| Trip timeline | rail segments and dots dimmed | name in `onSurfaceVariant` |
| Trip map | route split at the vehicle (`RouteProgress.of`) | `MapMarker.stale = true` (stop layers read it) |
| Journey map | each leg split at its vehicle or walker; finished legs dimmed whole | - |
| Journey leg card | rail gradient up to `RouteProgress.legFraction`; walk dots dimmed | from/to and intermediate names greyed |

Use `RouteProgress` (`core/model/.../Progress.kt`) and the token for any new view - don't pick a new alpha
or a new "passed" rule. Planner legs get the same logic through `RouteProgress.legStops`, which needs no
timetable. Not covered, because they show no single run: the line page (a pattern with several vehicles)
and the map screen's vehicle sheet (no route drawn).

# Colours

GTFS route colours **are** used whenever the feed provides them: rows parse `color`/`textColor` and the UI
uses `x.color ?: ModeColors.container(mode)`; `ColorContrast.readableOn` falls back to black or white below
WCAG AA (4.5:1). README sections 16 and 20 read as if this needed new work - it only needs the feed to
carry colours. Journey legs always use mode colours. A new `TransportMode` needs entries in both
`ModeColors.container` and `ModeShapes.badge` - badges differ by shape too, so lines are never told apart
by colour alone. Status colours are fixed hex values in `Tokens.kt`, chosen by dark/light only.

Ropeways have no line number, so `LineBadge` and the map's vehicle tag show `LineNames.badge` - the feed
name without its generic word and leading article ("Rittner Seilbahn" becomes "Rittner"), or the icon
alone when nothing short is left; TalkBack keeps the full name. Cable cars and funiculars use the
Material Symbols `gondola_lift` and `funicular` glyphs in `RopewayIcons.kt`, because the Compose icon
set has neither.

# Colour scheme

`TransitTheme` uses the app's own palette unless the user switches on wallpaper colours
(`UserSettings.dynamicColor`, off by default since 2026-10-11; Android 12+ only). "Pure black dark mode"
(`amoledDark`) applies `ColorScheme.trueBlack()` in dark mode: `background`, `surface`, `surfaceDim` and
`surfaceContainerLowest` become black, the other containers keep their tones so cards stay raised. Both
`MainActivity` and `WidgetConfigActivity` build the theme and must pass the same settings.

**There is no bright primary container.** `TransitTheme` applies `ColorScheme.mutedContainers()` to every
scheme (own, wallpaper, pure black): `primaryContainer`/`onPrimaryContainer` become the secondary pair, so
components that default to it (FABs, the contained loading indicator, time pickers) are muted too. Use
`secondaryContainer` in new code for emphasis; asking for `primaryContainer` gives the same colour.

# Component conventions (Material 3 Expressive)

Settled on 2026-10-13 after a review of every screen against the Expressive guidelines:

- **One of several options** is a `ConnectedToggleGroup` (stop departures/arrivals, results sort,
  alerts current/planned, theme), never filter chips. Stop departures/arrivals and alerts
  current/planned take `Modifier.weight(1f)` and fill the rest of their row with equal buttons.
  `equalWidth = false` lets each button take its label's width; wrap the group in `horizontalScroll`
  where the labels may not fit (results sort, four options). A choice with many options or long labels (start tab, language, "my position" button) is a
  `ChoiceRow` that opens a radio `ChoiceDialog` (`SettingsScreens.kt`). **Filter chips are for
  multi-select filters only** (map layers, "My stops and lines").
- Unselected `ConnectedToggleGroup` buttons default to `surfaceContainer`. On a card of that colour (the
  planner's routing-options card) they vanish: pass `uncheckedContainerColor` (the planner and the
  Settings theme group use `surfaceContainerHighest`). The `ToggleButtonDefaults` colour factory is
  `colors(...)` in this alpha, not `toggleButtonColors`.
- **One hero per screen** in `secondaryContainer` with 28 dp corners: the stop board's next departure
  (`NextDepartureCard` in `feature/common/Common.kt`, live departure boards only, and only while the first
  row is not an earlier departure; "earlier" sits above it; with `joined` its bottom corners are the inner
  4 dp and the rows below continue its group, `DepartureRow` indices offset by one) and the soonest catchable connection (`JourneyCard(emphasized = true)`). The open connection of the
  two-pane layout is `surfaceContainerHighest` with a primary outline, so it never looks like the hero.
  Departed connections are dimmed (alpha 0.7) and labelled.
- **Grouped lists**: `SegmentedListItem` where a list item fits, otherwise `groupShape(index, count)`
  (20 dp outside, 4 dp between, 2 dp apart) - the alerts list uses it for `AlertCard`, and Settings for every
  item of a `SettingsGroup` (one `item { }` per setting, on `surfaceContainer`).
- **Data freshness** is a pill: `FreshnessIndicator` draws its own container (the live container when
  live, `surfaceContainerHighest` otherwise, so it also works over the map) and takes an `extra` qualifier
  ("no vehicles reported" replaces the old map banner). Grey status colours tint only its icon - as text
  on the pill they fail 4.5:1. **It goes in the top bar** (`compact = true`, "Live · 16 s", the full
  sentence for TalkBack) wherever the screen has one: stop, trip, journey, alerts. Inline only where there
  is no bar - the map, the journey detail pane of the two-pane results layout, and the vehicle count on
  the line page. One indicator per screen: the journey's leg cards have none of their own.
- **Paging in time** (earlier/later departures, earlier/later connections) is `PageButton` in
  `feature/common/Common.kt`, a centred tonal button with an arrow.
- **The stop board, kept quiet at the user's request** (2026-10-13): lines row; then the time chip
  (`StopTimeChip`) with departures/arrivals and the wheelchair mark on the same row; then the direction
  choice, a full-width `ConnectedToggleGroup` with "All directions" as `null`. No distance (the board no
  longer asks for the location) and no "tap lines to filter" hint. Only the live pill is in the top bar.
- **Every `SegmentedListItem`** passes `colors = groupedItemColors()` (surfaceContainer) and
  `Modifier.groupGap(index, count)` (`feature/common/Common.kt`): the library's default segmented colours
  are indistinguishable from the page in dark mode, and without the gap the separators disappear.
- **The map's locate button is a FAB menu opened by a long press** (`LocateFabMenu` in
  `feature/map/MapScreen.kt`). A tap still centres on the user, against the spec's tap-to-open; a long press
  lists the saved stops, and picking one flies there and opens its sheet. `ToggleFloatingActionButton` has
  no long click, so a `pointerInput` on its modifier watches the `Initial` pass and consumes the release
  after a long press - otherwise the button's own click handler would take it as a tap too. TalkBack gets
  the long press as an `onLongClick` semantics action. `UserSettings.savedStopsHintShown` makes the "long
  press to jump to saved stops" snackbar appear once, the first time the map has a saved stop.
- **Settings is not a tab, and has one entry point.** `MapSearchBar` ends in the app mark
  (`drawable/ic_app_mark.xml`: the launcher's two stops and route, white on a `primary` circle), which
  opens a `DropdownMenu` with Saved, Lines and Settings. `TopLevelBar` has no settings button; About is
  reached from Settings. See [adding screens](app-screens-navigation-viewmodels.md).
- **The map's search bar** (`MapSearchBar`, `feature/search/MapSearch.kt`) is the one surface on the map
  with a shadow (3 dp): tone alone does not lift it off light tiles. `UserSettings.searchBar` puts it at the
  bottom by default, in the locate button's row, leaving 88 dp free on the button's side (the FAB menu's
  own 16 dp margin, the 56 dp button, 16 dp gap); the map credit then moves into the top column, under
  the freshness pill (`TransitMap(attribution = false)` plus `MapAttribution` there; on the same row as the
  pill it looked off, being shorter). At the top, the bar sits above the
  banners and the freshness pill, and the credit stays in the bottom corner away from the button. The locate button's centre position was removed for this
  (a stored BOTTOM_CENTER no longer parses and falls back to bottom right).
- Alert line chips are `LineBadge`s: `AlertScope.routes` keeps the GTFS `Route` behind each line name for
  mode and colour; names from planner notices have no route and get a neutral `OTHER` badge without icon.

# Reduced motion

`TransitTheme` reads `ANIMATOR_DURATION_SCALE == 0` once (`remember(context)`) into `LocalReducedMotion`
and picks the standard motion scheme. Only `TransitLoading` and the navigation transitions read it;
`Countdown`'s `AnimatedContent` and `StatusBanner`'s `AnimatedVisibility` rely on the system scale. New
animated components must check `LocalReducedMotion` themselves.

Date pickers go through `TransitDatePickerDialog` (`Pickers.kt`). The library's calendar/text-input
switch animates the content while resizing the dialog window every frame, which stuttered on phones
(issue #20; a faster motion scheme did not help, checked on a device 2026-10-17). So the dialog hides
the library toggle and puts its own in the headline, which recreates the picker in the other mode
(`key`), with no animation, keeping the selected date. Use it for any new date picker.

# What was checked

Code read of `core/map`, `core/designsystem` and `MainActivity.kt` on 2026-10-09. The online stop
fallback and the enlarged-map placement were checked on an emulator the same evening: stops appeared on
the map of a fresh install while the timetable was still importing, and the stop page's map enlarged at
the bottom. The colour-scheme options and the options card were checked on an emulator in light, dark
and pure black on 2026-10-11. The component conventions of 2026-10-13 were compiled, unit-tested and
linted; the user checked the stop board on a phone the same evening and asked for the header pill, the
single control row, `PageButton` and the row containers, which followed.
