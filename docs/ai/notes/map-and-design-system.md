---
type: Reference
title: Map and design-system conventions - MapContent, styles, attribution, colours, reduced motion
description: Map content is one immutable value re-pushed in full and marker ids must be unique across stops, vehicles and POIs; the dark style falls back to the light override; attribution and font are hard-coded to OpenFreeMap; feed route colours are already used; reduced motion is read once.
tags: [map, maplibre, design-system, colors, accessibility]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T15:00:00Z"
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

Code read of `core/map`, `core/designsystem` and `MainActivity.kt` on 2026-10-09.
