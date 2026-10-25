---
type: Reference
title: Bike boxes and e-bike sharing
description: Two mobility layers added 2026-10-25, each from two ODH requests joined by scode; Ecospazio's BIKE_CHARGER is e-bike sharing (bikes to rent, free docks), not charging for one's own bike; the bike box split by socket stays hidden because no site has a socket yet; out of service needs a current report; a full bike box site suggests the nearest one with space within 2 km.
tags: [mobility, odh, map, bike]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-25T13:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-25T13:30:00Z"
status: draft
---

The fifth and sixth mobility layers, added 2026-10-25 and off by default: STA's Bicibox bike boxes
(`MobilityKind.BIKE_PARKING`) and Ecospazio's e-bike sharing (`MobilityKind.EBIKE_SHARING`). The design
is the canvas "Bike parking and e-bike chargers" (Claude design artifact); the endpoints, coverage and
value meanings are in `docs/API_DISCOVERY.md`, section 4. This note records what is not obvious from the
code.

# Shape of the code

- Both sources live in `core/data/src/main/kotlin/org/southtyrol/transit/data/BikeParkingSource.kt` and
  implement `MobilityDataSource`, like `OdhChargingSource`: sites and latest values in two parallel
  requests, joined by `scode`. `OdhMobilitySource` throws for both kinds; the app module lists them
  explicitly.
- Details ride on `MobilityPoint.bikeParking` (`BikeParkingDetails`) and `MobilityPoint.ebikeSharing`
  (`EbikeSharingDetails`). `available` is free boxes or places, and for e-bikes the bikes to rent;
  `capacity` is total places or docks.
- `MobilityPoint.outOfService` (core/model) gathers the three grey rules - every charging point out of
  order, a bike box site `out of service`, an e-bike station not READY - and the map turns any of them
  grey without a count. For the two new kinds only a `LIVE` report counts, so a dead feed is not
  mistaken for a closure.
- The sheets are `app/src/main/kotlin/org/southtyrol/transit/feature/map/BikeSheets.kt`; the map screen
  picks them by which detail is set.
- `MobilityKind` names are stored in settings (enabled layers): the two new names are permanent.

# Things that surprise

- **The design called Ecospazio "e-bike charging".** The values show otherwise: `availableVehicles`
  equals the docks holding a bike and `freebay` plus that is the dock total, so it is a sharing system
  whose docks charge the shared bikes. The user chose to present it as e-bike sharing (2026-10-25); the
  layer, strings and sheet say "e-bikes to rent" and "free docks".
- **Most e-bike stations are closed in late October** (21 OFFLINE, 7 OUT_OF_SERVICE, 1 READY), so the
  layer is mostly grey tiles off season. That is the feed, not a bug.
- **No bike box has a socket yet**: every `stationPlaces[].type` is `withoutRefill` and
  `freeSpotsElectricBike` is always 0. The sheet's bikes / e-bikes rows appear only when a site has
  places of another type, so they are untested against real data.
- **The booking link is not in the feed**, so the design's "How to book" button was left out.
- **A full site points to the nearest site with space** (`BikeParking.nearestWithSpace`): only among the
  points already loaded for the visible map, within `NEAREST_MAX_METERS` (2 km), current and not out of
  service. A site just off screen is not found.
- **Bike sharing's switch can disappear**: `MobilityRepository.deadLayers` asks for the kinds in
  `REGION_CHECKED` (bike sharing only) across `Geo.SouthTyrol`; when no point has a value within 7 days
  the map and Settings drop the chip and the map stops fetching that layer. A failed check keeps the
  chip and is not cached; a result is kept for 6 hours. Bolzano last reported on 26 Sep 2026, so the
  chip is hidden from 3 Oct 2026 on.
- **The bike box glyph is drawn, not an icon**: Material has no "bike in a box", so the tile and the
  sheet draw an outlined box with a heavier lid around `PedalBike` (`MarkerImages.mobility`,
  `BoxedBike`). Its colour, `mobility-bike-parking` `#005662`, is not yet in the design system's tokens.
