# Feature parity

This compares South Tyrol Transit with the public features advertised for the official
südtirolmobil / altoadigemobilità app, as of 4 October 2026.

Statuses:

- **Implemented**
- **Partially implemented**
- **Public API limitation**
- **Requires backend**
- **Not possible through public API**
- **Intentionally excluded**

| Feature | Status | Notes |
|---|---|---|
| A-to-B journey planning | Implemented | STA EFA trip request. Depart-at/arrive-by, earlier/later, preferences (fastest, fewer changes, less walking), mode exclusion, walking speed, wheelchair option |
| Door-to-door walking portions | Implemented | EFA footpath legs with duration, distance and geometry. Start from current location (coordinates) or any address/POI |
| Address / POI / stop search | Implemented | EFA StopFinder online. Offline fallback to stops from the cached GTFS |
| Nearby stops | Implemented | Location requested only on tap. GTFS spatial query, or EFA coordinate search when no timetable is cached |
| Live departures | Implemented | GTFS schedule merged with GTFS-RT trip updates (countdown, delay, platform, cancellation, alert flag). EFA DM board when the timetable is not downloaded |
| Arrivals | Implemented | Departures/arrivals toggle on every stop |
| Timetables (stop / line) | Implemented | Stop boards and line pages: directions, ordered stops, upcoming trips from the offline GTFS |
| Realtime delays | Implemented | GTFS-RT delay propagation per spec. Explicit freshness with age, and stale data is never shown as live |
| Cancellations / skipped stops | Implemented | Trip `CANCELED` and stop `SKIPPED` handling, shown with text and icon |
| Live vehicle positions | Partially implemented | All vehicles in the public feed (~135 at a time) with bearing, freshness dimming/hiding, and trip association. Coverage depends on the provider |
| Service disruptions | Implemented | GTFS-RT alerts plus EFA AddInfo notices, deduplicated, multilingual (de/it/en/lld), active/planned, filter by line/stop text and by saved stops and lines; notices shown on the matching trip, cached offline as stale |
| Route maps | Implemented | GTFS shapes (simplified) for lines/trips, EFA geometry for journeys, MapLibre + OpenFreeMap |
| Favourite stops | Implemented | Local Room storage |
| Favourite lines | Implemented | Local Room storage |
| Saved places (Home/Work) | Implemented | Long-press a search result |
| Recent searches | Implemented | Recent journeys and places, clearable |
| Recurring / saved journeys | Implemented | Bookmark on the results screen. Opens directly in results |
| Push / notifications | Partially implemented / requires backend | Opt-in WorkManager check about every 30 min for new alerts on saved stops/lines. Not realtime. True push needs the backend in [`PUSH_BACKEND.md`](PUSH_BACKEND.md) |
| Journey price | Partially implemented (public API limitation) | Shown only when EFA returns `itdFare` (single and value-card adult prices), labelled "as reported by the planner". Otherwise the app says the fare is unavailable from the public API. No fare calculator |
| Ticket purchase | Not possible through public API | No public sales API. A Tickets screen explains this and links to official information. `TicketingProvider` interface reserved |
| Ticket validation | Not possible through public API | Never imitated. No QR codes |
| User account | Intentionally excluded / not possible | No public account API, and no account is needed: everything is local |
| Shared mobility (bike/car sharing) | Partially implemented | Optional map layers from ODH Mobility. South Tyrol data is sparse or stale, so the layers are off by default and stale values are flagged |
| Parking / Park & Ride | Implemented | ODH parking occupancy layer (on by default) with freshness |
| Offline timetable | Implemented | ~150 MB GTFS imported into a ~96 MB SQLite database, refreshed weekly on Wi-Fi with atomic swap |
| Multilingual UI | Implemented (en, de, it) / Partial (Ladin) | Ladin UI falls back to German. Ladin feed texts are shown when supplied |
