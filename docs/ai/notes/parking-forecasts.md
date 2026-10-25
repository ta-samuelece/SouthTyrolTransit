---
type: Reference
title: Car park forecasts
description: The ODH parking forecasts count occupied spaces, not free ones; a value's time is the moment it predicts and its issue time is that minus the horizon; forecasts arrive hourly at :05, so the chart is hidden only after 90 minutes; loaded per car park when its sheet opens, with two hours of free history, cached five minutes; placeholder capacities and stopped feeds show no chart.
tags: [mobility, odh, parking, forecast, map]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-25T10:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-25T10:30:00Z"
status: draft
---

The car park sheet's "Next hours" chart, added 2026-10-25. The design is the canvas "Parking forecast"
(Claude design artifact); the endpoints are in `docs/API_DISCOVERY.md`, section 4. This note records
what is not obvious from the code.

# Shape of the code

- Pure logic in `core/model/.../ParkingForecast.kt`: `ParkingForecasts.build` turns raw values into
  half-hour slots of free spaces and an outlook (likely full, filling up, getting emptier, about as now);
  `outlook` holds the thresholds (5% of the capacity, at least 10 spaces).
- `OdhParkingForecastSource` (`core/data/.../ParkingForecastSource.kt`) fetches; `ParkingForecastRepository`
  keeps each car park's result for five minutes. Both are wired in `AppModule`.
- `MapViewModel.selectMobility` loads it after a car park's sheet opens, like the stop sheet's departures;
  `ParkingForecastChart.kt` draws it inside `MobilitySheet`.

# Things that surprise

- **The values are occupied spaces.** `parking-forecast-60 = 172.7` for a car park with 114 cars in it
  means 173 cars, not 173 free. Free = capacity − value, so the conservative (`-low-`) and optimistic
  (`-high-`) ends swap when turned into free spaces.
- **`mvalidtime` is the predicted moment.** A horizon-*h* value was issued *h* minutes before it; the
  30-minute value is usually already in the past when read and is dropped. Times like 11:05 are shown
  on the nearest half hour.
- **Forecasts are issued hourly**, at :05. A normal forecast is therefore up to an hour old, so the
  staleness cutoff is 90 minutes (`ParkingForecasts.MAX_AGE`), not 60 - with 60 the chart would vanish
  shortly before every update. Some feeds stop for hours (Habitat Adriano on 2026-10-25): no chart.
- **The forecast can start far from now**: P03 had 114 cars while the forecast issued an hour earlier
  said 157 for that moment. The chart shows it as it is; the likely range is the honest signal.
- **Placeholders**: some Skidata car parks send capacity −1 and 9999 free; `build` returns null for a
  missing or non-positive capacity and for a free count above twice the capacity.
- The history request is `ParkingStation/free/<from>/<to>` with UTC path times; it is best effort and
  only the past bars depend on it. Quotes in the `where` value must be URL-encoded (`endpoint` does it);
  a raw `"` gets an HTML 400 from the server's Tomcat.
- The chart only appears for live car parks (`MobilityPoint.freshness == LIVE`), because its "now" bar is
  the sheet's current count.
