---
type: Reference
title: Map and design-system conventions - MapContent, styles, attribution, colours, reduced motion
description: Map content is one immutable value re-pushed in full and marker ids must be unique across stops, vehicles and POIs; without a timetable the map's stops come from the EFA coordinate search; maps inside pages use ExpandableMapPage and enlarge where their small card sits; the one convention for showing progress along a run; the dark style falls back to the light override; attribution and font are hard-coded to OpenFreeMap; feed route colours are already used; reduced motion is read once.
tags: [map, maplibre, design-system, colors, accessibility]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T20:30:00Z"
status: stable
---

# Map (`core/map`)

- Features use only the `TransitMap` wrapper (`MapLibreTransitMap` is `internal`); the wrapper also draws
  the credit line.
- Content is one immutable `MapContent`; on every change all five GeoJSON sources are re-set. Click
  lookup is `(content.stops + content.vehicles + content.pois).associateBy { it.id }` - **ids must be
  unique across all three lists**. `MapMarker.color` is the stroke for stops but the fill for vehicles and
  POIs.
- A `CameraRequest` is applied only when it differs from the last one; bump its `key` to re-apply the same
  request. `interactive` is applied only when the map is created.
- Clustering: stops up to zoom 13 (radius 42), vehicles up to zoom 10 (radius 36).
- **Styles**: `MainActivity` uses `MAP_STYLE_DARK`, else **`MAP_STYLE_LIGHT`**, else the OpenFreeMap dark
  default - so overriding only `map.styleUrl` gives a light map in dark mode. The OpenFreeMap defaults are
  repeated in `LocalMapStyle` in `TransitMap.kt`; keep both in sync.
- **Attribution** `© OpenStreetMap · OpenFreeMap` is hard-coded (the SDK's own attribution and logo are
  off), and all symbol layers use the font `Noto Sans Regular`. Switching tile provider means updating the
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
- **Lines**: `MapPolyline` has `opacity` and `dashed`. Dashed lines live in their own layer
  (`line-dasharray` cannot be data-driven on Android) - before 2026-10-09 `dashed` was written to the
  feature but never rendered, so walking legs drew solid.

# Progress along a run - one convention everywhere

Every view that shows a run marks what is already behind: the travelled part of the line and passed stops
are drawn at `Progress.PASSED_ALPHA` (0.35, in `core/designsystem/.../Tokens.kt`), the rest at full
strength. A stop is passed once its (predicted) departure is not after now (`RouteProgress.nextIndex`).

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

# Reduced motion

`TransitTheme` reads `ANIMATOR_DURATION_SCALE == 0` once (`remember(context)`) into `LocalReducedMotion`
and picks the standard motion scheme. Only `TransitLoading` and the navigation transitions read it;
`Countdown`'s `AnimatedContent` and `StatusBanner`'s `AnimatedVisibility` rely on the system scale. New
animated components must check `LocalReducedMotion` themselves.

# What was checked

Code read of `core/map`, `core/designsystem` and `MainActivity.kt` on 2026-10-09. The online stop
fallback and the enlarged-map placement were checked on an emulator the same evening: stops appeared on
the map of a fresh install while the timetable was still importing, and the stop page's map enlarged at
the bottom.
