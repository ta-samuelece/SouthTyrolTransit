# API discovery

Verified with live requests on **4 October 2026** (captured responses are in [`docs/fixtures`](fixtures)).
Every source below is used without authentication. Classification:

- **Official documented endpoint**: published by the data owner for developers.
- **Public open-source infrastructure endpoint**: production endpoint run by NOI Techpark / Open Data Hub, documented in its open-source repos.
- **Experimental / testing endpoint**: not used.
- **Deliberately not used**: listed at the end with reasons.

---

## 1. STA static timetable (GTFS)

| | |
|---|---|
| Provider | STA – Strutture Trasporto Alto Adige, published via Open Data Hub (NOI Techpark) |
| Purpose | Offline timetable: stops, routes, trips, stop times, calendars, shapes, translations |
| Primary endpoint | `GET https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw` (HTTPS, ZIP) |
| Fallback endpoint | `ftp://ftp.sta.bz.it/gtfs/google_transit_shp.zip`: the upstream `source` named in ODH [`datasets.yml`](https://github.com/noi-techpark/opendatahub-gtfs-api/blob/main/datasets.yml) |
| Classification | Public open-source infrastructure (ODH GTFS API; OpenAPI `openapi3.yml`) / official STA publication point (FTP) |
| Auth | None |
| Licence | CC0 (ODH dataset metadata `license: CC0`) |
| Sample captured | `docs/fixtures/gtfs-inventory.json` (file list + headers); full 147 MB ZIP used in `RealFeedTest` (not committed) |
| Refresh / caching | ODH caches upstream for 2 h (`cache_ttl: 7200`). The app checks weekly via WorkManager on unmetered network, sends `If-None-Match` / `If-Modified-Since`, and skips import when the SHA-256 is unchanged |
| Error behaviour | **Observed 502 Bad Gateway on 4 Oct 2026** for `/v1/dataset/...` and `/v1/realtime/...`; this is why the FTP fallback exists. Import failures never replace the active database |
| Confidence | Feed content: high. ODH API host availability: medium (observed outage) |
| Status | Implemented (`ScheduleStore`, `GtfsImporter`, `GtfsSchedule`) |

**Files actually present** (4 Oct 2026): `agency`, `calendar`, `calendar_dates`, `routes`, `trips`,
`stop_times` (1,101,984 rows), `stops` (6,436), `shapes` (8,628,981 points / 8,827 shapes, 570 MB raw),
`translations` (German stop names and trip headsigns only).
**Not present:** `feed_info`, `transfers`, `frequencies`, `fare_*`, `pathways`, `levels`, route colours.

Fields used:

- `stops`: `stop_id`, `stop_name`, `stop_lat/lon`, `stop_code`, `location_type`, `parent_station`, `platform_code`, `wheelchair_boarding`.
- `routes`: `route_id`, `route_short_name`, `route_long_name`, `route_type`, `agency_id`, plus `route_color` and `route_text_color` if ever supplied.
- `trips`: `trip_id`, `route_id`, `service_id`, `trip_headsign`, `direction_id`, `shape_id`, `wheelchair_accessible`.
- `stop_times`: arrival/departure (times ≥ 24:00 supported), `stop_sequence`, `pickup_type`, `drop_off_type`.
- `calendar` and `calendar_dates`.
- `shapes`: simplified with Douglas–Peucker at 4 m and stored as encoded polylines (96 MB database in total).
- `translations`: `table_name`, `field_name`, `language`, `translation`, `record_id`. Only ~80 % of trips have headsign translations, so the importer derives a headsign-text dictionary for the rest.

FTP fallback notes:

- Android's `URLConnection` has no FTP support, so the app uses a minimal passive-mode client (`FtpDownload`) with `SIZE`/`MDTM`, and resumes interrupted transfers with `REST`.
- Verified against the live server from the JVM: full download and import of 146,878,396 bytes.
- On the Android emulator (QEMU user-mode networking), FTP data transfers arrived corrupted (truncated by a few bytes). ZIP validation rejected them, as designed, and the working schedule was kept. Verify on physical devices.

Observations:

- Stop ids follow `it:22021:<station>:<area>:<platform>`. The first three segments are the station key, and they equal the EFA `gid`. That is how GTFS and EFA are joined.
- `agency_timezone` is `Europe/Rome`; the importer rejects other zones.
- Route types present: 3 (bus), 2 (rail), 7 (funicular / cable car).

---

## 2. STA GTFS-Realtime

| | |
|---|---|
| Provider | STA via Open Data Hub |
| Purpose | Trip updates (delays, cancellations, skipped stops), vehicle positions, service alerts |
| Primary endpoints | `https://files.opendatahub.com/gtfs-rt/feeds/sta/{trip-updates,vehicle-positions,service-alerts}.pb` |
| Fallback | `https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/{feedType}` (content-negotiated JSON/protobuf) |
| Classification | Public open-source infrastructure: the `files.` URLs are the `realtime.feeds.*.sources.pb` entries in ODH `datasets.yml`, i.e. what the GTFS API itself proxies |
| Auth | None |
| Licence | CC0 |
| Samples | `trip_updates.pb`, `vehicle_positions.pb`, `service_alerts.pb`, `trip_pb.bin`, `vehicles.json`, `alerts.json` |
| Format | GTFS-RT 2.0, `FULL_DATASET`. Protobuf is parsed with `org.mobilitydata:gtfs-realtime-bindings` 0.2.0 |
| Refresh | ODH `cache_ttl: 0`. The app polls trip updates and vehicles at most every 20 s, and only while a live screen is visible (30 s on boards, 20 s on map/trip). Alerts are polled at most every 2 min. No background polling except the opt-in alert check (≥ 30 min) |
| Fields used | TripUpdate: `trip_id`, `route_id`, `start_date`, `schedule_relationship`, `stop_time_update` (`stop_sequence`, `stop_id`, arrival/departure `time`/`delay`, `SKIPPED`, `NO_DATA`), trip `delay`, `timestamp`. VehiclePosition: `position`, `bearing`, `trip`, `current_status`, `stop_id`, `occupancy_status`, `timestamp`, `vehicle.label`. Alert: `header_text`/`description_text` (de, it, en, lld), `informed_entity` (route/stop/trip), `active_period`, `cause`, `effect`, `severity_level`, `url` |
| Error behaviour | Each feed fails independently and is tracked with its own `FeedStatus`; boards fall back to schedule and say so |
| Confidence | High. 45/45 trip ids in a captured feed matched the static GTFS (`RealFeedTest`). ~57 trip updates and ~135 vehicles at a time; coverage is partial |
| Status | Implemented |

Staleness policy (`FreshnessPolicy`):

- A trip update older than **5 min** is not applied.
- A feed not fetched for **3 min** counts as unavailable.
- Vehicles are drawn normally up to **2 min**, dimmed until **10 min**, then hidden.

---

## 3. STA EFA XML interface (journey planner)

| | |
|---|---|
| Provider | STA (Mentz EFA), documented in the South Tyrol open data portal dataset *“Servizi web del trasporto pubblico locale in Alto Adige”* (`docs/fixtures/efa_catalogue.txt`) |
| Base | `https://efa.sta.bz.it/apb/` |
| Classification | Official documented endpoint |
| Auth | None |
| Licence | CC0 |
| Docs | *dokumentation_xml_schnittstelle_apb* (Mentz), sections StopFinder, Trip, DM, AddInfo, Coord |

| Request | Purpose | Key parameters used | Sample |
|---|---|---|---|
| `XML_STOPFINDER_REQUEST` | Place search (stops, streets, addresses, POIs) and reverse geocoding | `type_sf=any\|coord`, `name_sf`, `anyObjFilter_sf=0`, `anyMaxSizeHitList` | `efa_stopfinder_list.xml`, `search_de.bin`, `efa_search.txt` (not-identified case) |
| `XML_TRIP_REQUEST2` | Door-to-door journeys incl. walking legs, realtime, fares | `type_/name_origin\|destination` (stop id, stateless id, `coord`), `itdDate/Time`, `itdTripDateTimeDepArr=dep\|arr`, `routeType=LEASTTIME\|LEASTINTERCHANGE\|LEASTWALKING`, `changeSpeed`, `imparedOptionsActive`+`wheelchair`+`lowPlatformVhcl`, `excludedMeans=checkbox`+`exclMOT_<id>`, `useRealtime=1`, `coordListOutputFormat=STRING` | `efa_trip_ok.xml`, `efa_trip_walk.xml`, `efa_trip.txt` (error -8020) |
| `XML_DM_REQUEST` | Departure/arrival board (used when no offline timetable exists) | `type_dm=any`, `name_dm=<gid>`, `mode=direct`, `useRealtime=1`, `itdDateTimeDepArr` | `efa_dm.xml` |
| `XML_COORD_REQUEST` | Nearby stops without offline timetable | `coord`, `inclFilter=1`, `type_1=STOP`, `radius_1` | `efa_coord.xml` |
| `XML_ADDINFO_REQUEST` | Operator notices (65 items vs. 14 GTFS-RT alerts on 4 Oct 2026), multilingual (de, it, en, ld1, ld2) | `filterPublished=1`, `filterShowLineList=1`, `filterShowStopList=1` | `efa_addinfo.xml` |

Fields used from trip responses:

- `itdPartialRoute` legs: `itdPoint` departure/arrival with `itdDateTime` (estimated) vs `itdDateTimeTarget` (planned), `platformName`, `gid`, coordinates.
- `itdMeansOfTransport`: `motType`, `type` 99/100 = footpath, `shortname`, `trainType`/`trainNum`, `destination`, operator.
- `itdStopSeq`: intermediate stops.
- `itdPathCoordinates`: leg geometry.
- `infoLink`: leg notices.
- `itdFootPathInfo`: walking distance.

**Fares.** `itdFare/itdUnifiedTicket` lists adult prices per span of transit legs (`fromPR..toPR`). The app shows only the whole-journey span: `STANDARD` (single ticket) and `VALUECARD`. Example: Bozen → Meran Thermen, train + bus: €9.00 single / €5.16 value card. These are labelled as planner-reported and are never computed by the app.

Other behaviour:

- **Language quirk.** `XML_STOPFINDER_REQUEST` with `language=en` answers `notidentified` for every query, while `de` and `it` work. Trip and DM requests work in English. Name matching is also language-specific ("Bozen Bahnhof" only matches in `de`, "Bolzano stazione" only in `it`), so the app queries `de` and `it` in parallel and merges the results by `matchQuality`, keeping the app language's spelling.

- **Errors.** `itdMessage type="error"` codes such as `-8010` (identified) are informational. The app only reports failure when an `itdOdvName` is `notidentified` and no routes are returned. Responses are UTF-8, DTDs are rejected (XXE guard), and payloads are capped at 15 MB.
- **Caching.** No HTTP cache headers are sent. Results are cached in memory, and alerts are cached on disk for offline display (marked stale). AddInfo is refetched at most every 10 min.
- **Confidence.** High: the interface is stable (EFA 10.6). The `infoLinkURL` values point to an internal 10.240.x host, so the app never opens them.
- **Status.** Implemented (`EfaClient`, `EfaXml`).

---

## 4. Open Data Hub Mobility API v2 (optional layers)

| | |
|---|---|
| Provider | NOI Techpark Open Data Hub |
| Endpoint | `https://mobility.api.opendatahub.com/v2/flat,node/{ParkingStation\|BikesharingStation\|CarsharingStation}/{free\|number-available}/latest` |
| Filters | `where=sactive.eq.true,scoordinate.bbi.(w,s,e,n,4326)`, `select=scode,sname,scoordinate,sorigin,mvalue,mvalidtime,smetadata.capacity` |
| Classification | Public open-source infrastructure (production API) |
| Auth | None for open data |
| Licence | Per origin; the open records used here are published as open data by ODH |
| Freshness (4 Oct 2026) | Parking (FAMAS / municipalities / skidata): current (minutes old). Bike sharing in South Tyrol: 2 stations, last value Sept 2026 (stale). Car sharing: sparse and partly years old |
| App policy | Parking is on by default. Bike and car sharing are off by default. Values older than 3 h are shown as "no current availability", and values older than 7 days are dropped (dead sensors). Every layer fails independently (`MobilityRepository`) |
| Status | Implemented behind toggles |

---

## 5. Base map

| | |
|---|---|
| Provider | OpenFreeMap (OpenMapTiles schema, OSM data) |
| Endpoints | `https://tiles.openfreemap.org/styles/positron` (light), `/styles/dark` (dark) |
| Auth | None, no API key |
| Licence | Tiles free to use; data © OpenStreetMap contributors (ODbL). Attribution is shown by MapLibre and on the About screen |
| Override | `map.styleUrl` / `map.styleUrlDark` in `local.properties`, or `TRANSIT_MAP_STYLE_URL[_DARK]` env vars |
| Status | Implemented via MapLibre Android 13.6.1 behind the provider-agnostic `TransitMap` API |

---

## Deliberately not used

| Source | Reason |
|---|---|
| `*.testingmachine.eu` (e.g. the OTP and Amarillo carpool URLs in ODH `datasets.yml`) | Testing infrastructure; not declared production-ready |
| NOI OpenTripPlanner / Transmodel GraphQL | Not a documented public production service for third parties; EFA is the official planner |
| `files.opendatahub.com/siri-lite/*` (SIRI ET/SX/VM) | Same information as GTFS-RT and EFA AddInfo. Not needed, so not integrated, to avoid duplicate alert sources |
| Official app ticketing/account APIs | Private and authenticated. Not reverse-engineered, per project rules |
| EFA `infoLinkURL` targets | Point to an internal network address (`10.240.x.x`) |
| `XML_TTB_REQUEST`, `XML_STT_REQUEST`, `XML_ROP_REQUEST` (line timetable, stop timetable, route maps / PDFs) | GTFS covers these functions offline. EFA PDF/map outputs are not needed |
