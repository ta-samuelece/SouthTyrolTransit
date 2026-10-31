---
type: Reference
title: Map and design-system conventions - MapContent, styles, attribution, colours, reduced motion
description: Map content is one immutable value pushed layer by layer and marker ids must be unique across stops, vehicles and POIs; without a timetable the map's stops come from the EFA coordinate search; maps inside pages use ExpandableMapPage and enlarge where their small card sits; the one convention for showing progress along a run; the dark style falls back to the light override; four map types from one provider catalogue, Province rasters under the transit layers, credit per style and the font every style must serve; feed route colours are already used; the colour scheme (own palette by default, optional wallpaper colours and pure black); which Material 3 Expressive component to use for a choice, a hero, a grouped list and data freshness; the map's locate button is a FAB menu opened by a long press, with the layers button above it; reduced motion is read once.
tags: [map, maplibre, design-system, colors, accessibility]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-29T12:00:00Z"
status: stable
---

# Map (`core/map`)

- Features use only the `TransitMap` wrapper (`MapLibreTransitMap` is `internal`); the wrapper also draws
  the credit line.
- Content is one immutable `MapContent`, but each layer is pushed by its own effect keyed on that list
  (lines; stops with `clusterStops`; vehicles; POIs) plus the style, so a vehicle poll re-sets only the
  vehicle source - building features runs on the main thread. Keep lists stable (same instance when
  unchanged) or the split buys nothing: build each page's `MapContent` (and `CameraRequest`) inside
  `remember` keyed on its inputs, as every map screen does. `MapContent` is `@Immutable`, so a fresh
  instance on each recomposition (a clock tick, a poll) is compared marker by marker, in the map and
  again in each layer's effect keys. Click lookup checks stops, then vehicles, then POIs by id -
  **ids must be unique across all three lists**, or a stop shadows the vehicle or POI it collides with. `MapMarker.color` is the stroke for stops and the fill for vehicles and
  journey endpoints; mobility points use their kind's colour unless `color` overrides it (grey
  status-stale for anything `MobilityPoint.outOfService` says is closed).
- **Map sheets carry no data-source line**: no "Data: ..." credit at the bottom of a stop, parking,
  charger, bike box or e-bike sheet (removed at the user's request on 2026-10-25); sources are credited
  in the map's attribution and on the About screen.
- **Stop markers carry mode and alerts** (design of 2026-10-28, canvas "Stop Markers"): on the map tab
  `MapMarker.mode` is the station's `TransportMode.dominant` mode (train, cable car, funicular, tram,
  city bus, bus, in that order) from `MapRepository.stopModes` - one `stationModes` query over the
  timetable's stop_times, cached per station key for the session; online stops have none - and
  `color` is that mode's colour, so bus stops ring teal. Train, cable car and funicular stations are
  dots with a 3.5 dp ring below zoom 15 and a `stopDisc` image (mode icon on the mode colour, white
  ring, amber when saved) from 15. `MapMarker.alert` - an alert `active` now whose `stops` name the
  station (`AlertMatcher.affectsStop`; line-wide alerts would mark every stop) - adds a red badge
  (`stopAlert`, status-cancelled red, white "!" from zoom 15) on the upper right edge; its `icon-offset`
  follows the marker's radius by zoom. Selected stops get a white gap and an on-surface ring (a halo
  circle layer below the dots; the disc image draws its own). `addStopLayers` builds these layers for
  both stop sources; keep every zoom expression at the top level of its property. The other maps pass
  no mode and keep their line-coloured dots. GTFS has no city-bus route type, so from the timetable
  every bus stop is `BUS`.
- **Marker shapes say the kind**: stops are circle layers (stations also disc images); mobility points (parking, bike and car
  sharing, EV charging, bike boxes, e-bike sharing) are rounded-square images with a Material icon (bike
  boxes draw a bike inside a box) and, from zoom 16, a pill with
  `MapMarker.label` (the live count); `MapMarker.badge` adds an amber bolt on the opposite corner (a
  charging station with a DC point of 50 kW or more); vehicles are images of a mode-icon circle plus the line number in a
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
- **Styles are one design per theme**: a `MapStyle` has a light, a dark and a pure-black URL, and they must
  be the same map in other colours - the user rejected a dark mode that changed which roads, shops and
  buildings appear. The standard map is OpenFreeMap Positron (light), Fiord (dark) and Dark Matter
  (`/styles/dark`, only with pure black on: `LocalMapPureBlack`, provided by `MainActivity` as dark mode
  and `amoledDark`); all three are recolourings of Positron. A style with no dark variant uses its light
  one in every theme rather than borrowing another design. `MainActivity` builds the standard style
  from the build override: `MAP_STYLE_DARK`, else **`MAP_STYLE_LIGHT`**, serves both dark and pure black -
  so overriding only `map.styleUrl` gives a light map in dark mode. The OpenFreeMap defaults are repeated
  in `LocalMapStyle` in `TransitMap.kt`; keep both in sync.
- **Map types** (`MapBase` in `core/model`, setting `mapBase`): `MapBase.style(standard)` in `TransitMap.kt`
  is the one provider catalogue, and `MainActivity` provides its result as `LocalMapStyle`, so every map
  (map tab, page maps, map picker) follows the choice. STANDARD keeps the build override; DETAILED is
  OpenFreeMap Liberty in every theme (it has no dark variant); AERIAL and TOPOGRAPHIC are the standard style plus `MapStyle.raster`, the
  Province of Bolzano WMTS (details in `docs/API_DISCOVERY.md` section 5) limited to its bounds and to
  zoom 10 and up, so the vector style shows outside South Tyrol. `installRaster` adds the raster before
  `installLayers`: above the base style, below every transit layer. The style effect is keyed on URL,
  raster and theme; the camera and click listeners are registered once, in their own effect, or every
  style change would stack another copy.
- **Attribution** is `MapStyle.credit`, drawn by `MapAttribution` (the SDK's own attribution and logo
  are off); a style must name every source it draws (ODbL for OSM). All symbol layers use the font `Noto
  Sans Regular`: a new vector style must serve it from its glyphs URL (every OpenFreeMap style does,
  checked 2026-10-22), or labels may not render.

- **Stops without a timetable**: `MapRepository.stops` reads `stopsIn` from the timetable when one is
  installed, otherwise the EFA coordinate search (`EfaClient.nearbyStops`, `max = 400`) around the
  viewport centre, radius = half the diagonal capped at 3 km, filtered to the box. Online stops use the
  EFA global id as `id` and `stationKey`, the same key as timetable stations, so saved-stop highlighting
  and the stop page work for both. The map screen and the map picker both go through it.
- **Maps inside pages** (trip, journey, line, stop) use `ExpandableMapPage` + `CompactMapCard`
  (`app/.../feature/common/ExpandableMap.kt`): a static card in the list, enlarged to 60% of the page's
  height outside the list so its gestures never fight the list's scrolling; back shrinks it. The page is
  one custom `Layout` (header, enlarged map, list in the rest), not a `BoxWithConstraints`, so the whole
  page is not subcomposed during measurement. `CompactMapCard` pins its list item
  (`LocalPinnableContainer`) while the page is shown: unpinned, scrolling it out of view disposed the
  `MapView`, whose `onDestroy` blocks the main thread 20-50 ms on MapLibre's render thread, and scrolling
  back built a new map (Perfetto, 2026-10-31). `placement` puts
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
| Trip timeline | rail segments and dots dimmed | name in `onSurfaceVariant` (`StopTimelineRow`, `feature/common/StopTimeline.kt`) |
| Trip map | route split at the vehicle (`RouteProgress.of`) | `MapMarker.stale = true` (stop layers read it) |
| Journey map | each leg split at its vehicle or walker; finished legs dimmed whole | - |
| Journey leg card | the trip timeline's rows (`LegStops`: rail, dots, vehicle before the next stop, by time); the run before the boarding stop on a dashed rail, passed calls dimmed the same way; walk dots dimmed | as on the trip timeline |

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
- **The user's dot is not a marker** (2026-10-28): `TransitMap(user = UserLocation(...))` draws it in its
  own source and layers on top of everything (accuracy halo in true metres via a zoom-exponential radius,
  the `user-cone` image rotated by the heading, then the dot), so a fix or compass reading updates only
  that source instead of rebuilding `MapContent`. On the main map `MapViewModel.trackUser()` runs while the
  screen is STARTED: `LocationProvider.updates` every 1.5 s and `HeadingProvider.headings()` (rotation
  vector, remapped for the display rotation, plus declination for true north), skipping changes under 2°.
  It starts at once when location is already allowed, otherwise after the locate button's first fix; the
  camera never follows. Other maps (pickers, the followed journey) still pass a `MarkerKind.USER` marker.
- **The map's locate button is a FAB menu opened by a long press** (`LocateFabMenu` in
  `feature/map/MapScreen.kt`). A tap still centres on the user, against the spec's tap-to-open; a long press
  lists the saved stops, and picking one flies there and opens its sheet. `ToggleFloatingActionButton` has
  no long click, so a `pointerInput` on its modifier watches the `Initial` pass and consumes the release
  after a long press - otherwise the button's own click handler would take it as a tap too. TalkBack gets
  the long press as an `onLongClick` semantics action. `UserSettings.savedStopsHintShown` makes the "long
  press to jump to saved stops" snackbar appear once, the first time the map has a saved stop.
- **The layers button** (`MapLayersButton`, `feature/map/MapLayersSheet.kt`) is a FAB the size of
  the locate button, in a grey tone (`surfaceContainerHigh`), stacked above it in its corner (84 dp up; above the bottom search bar
  when the locate button is hidden). It hides while the locate menu is open, whose items rise over it,
  and Settings > Map can turn it off (`showLayersButton`). A top-corner placement with a left/right
  setting was tried and dropped by the user. Its sheet holds the map type as four selectable tiles (one of four: not chips) and the same layer chips as Settings > Map (`ToggleChip`
  is shared); Settings > Map also has a "Map type" `ChoiceRow`.
- **Settings is not a tab, and has one entry point.** `MapSearchBar` ends in the app mark
  (`drawable/ic_app_mark.xml`: the launcher's two stops and route, white on a `primary` circle in a
  `primary` ring), `SearchBarMenuButton` in `feature/search/SearchBarMenu.kt`. It opens a FAB menu (design
  of 2026-10-18): Saved, the language (its value only; opens the five choices in place, Back returns),
  the theme (its value only; each tap cycles System, Light, Dark through `SettingsRepository.setTheme`,
  the menu stays open; primary while not System) and Settings. The button only reports the mark's root
  bounds; `MapScreen` then draws `SearchBarMenu` as the last layer of its own layout, with a full-size
  layer behind it that turns an outside tap into a close, and a `BackHandler`. **Not a `Popup`:** a popup
  window, created on every opening and sized to its content, was resized on each frame of the items'
  growth - the opening stuttered and the items seemed to slide in from the screen's edge. `AtAnchor`
  places the menu by the mark's bounds less its own `positionInRoot`. With the bar at the bottom it holds
  Material's `FloatingActionButtonMenu`, whose button slot is `MarkButton` exactly over the mark (the menu
  pads 16 dp beside and below its button): the mark morphs into the close X (ring fades, disc grows,
  icons cross-fade with a quarter turn), and back on close; with the bar at the top, where Material's menu
  cannot open downward, a mirrored column of look-alike pills drops below it. Closing to the map folds
  the menu first (`CLOSE_MS`); Saved, Settings and a language pick remove it at once, so nothing lingers
  over the next screen. Lines is no longer in the menu nor in the search view, at the
  user's request: the all-lines screen (`LinesRoute`) has no entry point left, single lines open from
  search results and stop boards. `TopLevelBar` has no settings button; About is reached from Settings.
  See [adding screens](app-screens-navigation-viewmodels.md).
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
