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
- `trips`: `trip_id`, `route_id`, `service_id`, `trip_headsign`, `direction_id`, `shape_id`, `wheelchair_accessible`, `trip_short_name` (train numbers), `bikes_allowed`. The last two were empty in every sampled row (buses only).
- `stop_times`: arrival/departure (times ≥ 24:00 supported), `stop_sequence`, `pickup_type`, `drop_off_type`, `shape_dist_traveled` (metres in the STA feed, stored rounded; distances per stop, run length, a leg's distance).
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
| Endpoint | `https://mobility.api.opendatahub.com/v2/flat,node/{ParkingStation\|BikesharingStation\|CarsharingStation}/{free\|number-available}/latest`; charging stations, bike boxes and e-bike sharing below |
| Filters | `where=sactive.eq.true,scoordinate.bbi.(w,s,e,n,4326)`, `select=scode,sname,scoordinate,sorigin,mvalue,mvalidtime,smetadata.capacity` |
| Classification | Public open-source infrastructure (production API) |
| Auth | None for open data |
| Licence | Per origin; the open records used here are published as open data by ODH |
| Freshness (4 Oct 2026; car sharing 24 Oct; bike sharing 25 Oct) | Parking (FAMAS / municipalities / skidata): current (minutes old). Bike sharing: Bolzano (`BIKE_SHARING_BOLZANO`, 15 active stations) last reported `number-available` on 26 Sep 2026, so all are dropped after 7 days; Merano's bikes (`Bicycle`, `BIKE_SHARING_MERANO`) stopped on 31 Dec 2025; Papin Sport's rental points report only an always-1 `availability` and `isClosed`, with no counts, and are not shown. Car sharing: AlpsGo, 48 of 53 stations updated within 3 h on 24 Oct 2026 |
| App policy | Parking is on by default. Bike and car sharing, EV charging, bike boxes and e-bike sharing are off by default. Values older than 3 h are shown as "no current availability", and values older than 7 days are dropped (dead sensors). Bike sharing's switch is hidden while no station in the whole region has a value within 7 days (`MobilityRepository.deadLayers`, a South Tyrol-wide request at most every 6 h). Every layer fails independently (`MobilityRepository`) |
| Status | Implemented behind toggles |

### Car park forecasts (`OdhParkingForecastSource`)

Fetched only when a car park's sheet is opened, two requests at once:

| Request | Path and `where` |
|---|---|
| Forecast | `ParkingStation/*/latest`, `where=scode.eq."<scode>",tname.ire.forecast`, `select=tname,mvalue,mvalidtime` (about 4.5 KB) |
| History | `ParkingStation/free/<from>/<to>` (UTC, `yyyy-MM-ddTHH:mm`), `where=scode.eq."<scode>"` (7 to 35 KB for 2½ hours); best effort |

- **Data types:** `parking-forecast-30` … `-240` every 30 minutes, plus `-low-` (conservative) and `-high-` (optimistic) for each, and `-rmse-` (last computed October 2025, ignored).
- **The values are OCCUPIED spaces**, not free ones (checked against the current `occupied` counts of 38 car parks on 25 Oct 2026); the app shows free spaces as capacity minus the forecast, so the low and high ends swap.
- **`mvalidtime` is the predicted moment**; a value for horizon *h* was issued *h* minutes earlier. Forecasts are issued once an hour at :05, so the newest is up to an hour old; the app hides the chart when it is more than 90 minutes old (`ParkingForecasts.MAX_AGE`).
- **Coverage (25 Oct 2026):** 71 car parks have forecast data, 38 of them current (Skidata, FAMAS P03–P16, Merano municipal, STA Marlengo); GARDENA's and some Skidata car parks have stopped updating (e.g. Habitat Adriano, last issued two hours earlier).
- **Placeholders:** some Skidata car parks report capacity −1 and 9999 free; they get no chart.
- Fixtures: `docs/fixtures/odh_parking_forecast.json`, `odh_parking_free_history.json` (P03 Piazza Walther, 25 Oct 2026 10:03 UTC).

### EV charging stations (`OdhChargingSource`)

Three requests for the visible area, run together and joined in the app:

| Request | Path and `select` |
|---|---|
| Stations | `EChargingStation`, `select=scode,sname,scoordinate,sorigin,smetadata` |
| Charging points | `EChargingPlug`, `select=scode,pcode,smetadata`; a point's `pcode` is its station's `scode` |
| Status | `EChargingPlug/echarging-plug-status,echarging-plug-status-ocpi,echarging-plug-status-oicp/latest`, `select=scode,mvalue,mvalidtime`; best effort, stations still show without it |

- **Coverage (24 Oct 2026, South Tyrol):** about 780 active stations. Neogy (about 450, OCPI) reports point status; route220 (about 80), BFE, DRIWE and IIT report some; about 230 stations from one spreadsheet import (origin `1ucCQz…`, e.g. Be Charge, Leitner Energy) have no status and often no address.
- **Status vocabularies:** OCPI `AVAILABLE`, `CHARGING`, `BLOCKED`, `RESERVED`, `INOPERATIVE`, `OUTOFORDER`, `UNKNOWN`, `REMOVED`; OICP `Available`, `Occupied`, `OutOfService`, `Unknown`; route220 a bare `0` or `1`, meaning undocumented. All are mapped in `Charging.status`; `REMOVED` drops the point, the numbers count as unknown.
- **Change-only reporting:** a status is published when it changes, so most are hours or days old while still true. The app counts a status as current for up to 7 days (`FreshnessPolicy.CHARGING_MAX_AGE`) and shows its time ("Status as of 16:00"), never "Live".
- **Point descriptions:** OCPI `smetadata.connectors[]` (`standard`, `power_type`, `max_electric_power` in W, `format` CABLE or SOCKET); older feeds `smetadata.outlets[]` (`outletTypeCode`, `maxPower` in kW, `hasFixedCable`).
- **Not chargers:** IIT's hydrogen dispenser (`outletTypeCode` "700 bar small vehicles") and stations with `state` PLANNED are left out.
- **Station names** are often operator codes ("BZ_P_VITTORIA", "IT*BEC*EH000137*1"); the app then shows the address, or "Charging station" when there is none. Neogy cities are upper case ("BOLZANO - BOZEN") and are title-cased.
- **No prices** are published by any operator.
- Fixtures: `docs/fixtures/odh_charging_stations.json`, `odh_charging_plugs.json`, `odh_charging_status.json` (central Bolzano, 24 Oct 2026).

### Bike boxes (`OdhBikeParkingSource`)

STA's lockable bike parking at stations (Bicibox). Two requests for the visible area, run together and joined by `scode`:

| Request | Path and `select` |
|---|---|
| Sites | `BikeParking`, `select=scode,sname,scoordinate,sorigin,smetadata` |
| Values | `BikeParking/free,freeSpotsRegularBike,freeSpotsElectricBike,usageState/latest`, `select=scode,tname,mvalue,mvalidtime` |

- **Coverage (25 Oct 2026):** 24 active sites, origins `bicincitta` (21) and `loewenbytes` (3), values every 10 minutes. `smetadata.type` is `bikeBoxGroup` (individual boxes, 4 to 20) or `veloHub` (a shared depot, 12 to 176 places).
- **Metadata:** `names` and `addresses` in de, it, en and lld; `totalPlaces`; `stationPlaces[]` with a `type` (every place is `withoutRefill` today, so no site has a socket yet); `netex_parking` (`layout` covered, `surveillance`, `reservation` reservationRequired: the same for every site).
- **`sname`** reads "Bressanone / Bicibox individuali": the Italian place name, then the kind of site; the app keeps the place.
- **`usageState`** is mostly `unknown`, `in service` for loewenbytes and `out of service` for one site; only the last changes what the app shows (grey, no count).
- **A test site** ("Comune DEMO / Bicibox individuali demo") is published with the real ones; the app drops names containing "demo".
- **Not published:** how to book a box, and prices.
- Fixtures: `docs/fixtures/odh_bikeparking_stations.json`, `odh_bikeparking_values.json` (five sites, 25 Oct 2026).

### E-bike sharing (`OdhEbikeSharingSource`)

Ecospazio's e-bike sharing, published as `BIKE_CHARGER` (stations) and `BIKE_CHARGER_BAY` (their docks). The app reads the stations only:

| Request | Path and `select` |
|---|---|
| Stations | `BIKE_CHARGER`, `select=scode,sname,scoordinate,sorigin,smetadata` |
| Values | `BIKE_CHARGER/availableVehicles,freebay,state/latest`, `select=scode,tname,mvalue,mvalidtime` |

- **Meaning, worked out from the values (25 Oct 2026):** `availableVehicles` is the number of e-bikes docked and ready to rent (it equals the docks whose `usageState` is `NONE`), `freebay` the empty docks (`freebay` + `availableVehicles` = `smetadata.totalBays` at every station). Despite the type's name these are not chargers for one's own bike; the docks charge the shared e-bikes. A dock's `usageState` `RICARICA` marks an empty dock.
- **`state`:** READY, OFFLINE or OUT_OF_SERVICE; on 25 Oct 2026 one of 29 stations (Völs am Schlern) was READY. The app treats anything but READY as closed: grey, no count.
- **Coverage:** 29 stations on the Ritten, in Gröden, Eggental, Sarntal, Kastelruth and around, values every 10 minutes. Names read "Italian - German" and one carries a tab.
- **Docks (`BIKE_CHARGER_BAY`, `pcode` = the station's `scode`)** have `state` READY / OUT_OF_SERVICE / FAULTY and `usageState`; some dock values date from February 2024. Not used.
- Fixtures: `docs/fixtures/odh_ebike_stations.json`, `odh_ebike_values.json` (three stations: READY, OUT_OF_SERVICE, OFFLINE; 25 Oct 2026).

---

## 5. Base map

| | |
|---|---|
| Provider | OpenFreeMap (OpenMapTiles schema, OSM data) |
| Endpoints | `https://tiles.openfreemap.org/styles/positron` (light), `/styles/fiord` (dark), `/styles/dark` (pure black): all three are Positron's layout in other colours. `/styles/liberty` for the Detailed map type in every theme (no dark variant exists) |
| Auth | None, no API key |
| Licence | Tiles free to use; data © OpenStreetMap contributors (ODbL). Attribution is shown by MapLibre and on the About screen |
| Override | `map.styleUrl` / `map.styleUrlDark` in `local.properties`, or `TRANSIT_MAP_STYLE_URL[_DARK]` env vars |
| Status | Implemented via MapLibre Android 13.6.1 behind the provider-agnostic `TransitMap` API |

### Province of Bolzano raster layers (Aerial and Topographic map types)

| | |
|---|---|
| Provider | Autonomous Province of Bolzano, via the municipalities' consortium MapProxy |
| Endpoint | `https://geoservices.buergernetz.bz.it/mapproxy/root/service`, WMTS 1.0.0 KVP `GetTile` (the capabilities have no RESTful `ResourceURL`) |
| Layers | `p_bz-Orthoimagery:Aerial-2023-RGB` (aerial), `p_bz-BasemapImagery:TopographicMap-Dynamic` (topographic); `STYLE=default`, `FORMAT=image/png` |
| Grid | `TILEMATRIXSET=EPSG_3857`, standard Web Mercator 256 px tiles, levels `00`-`24`; MapLibre's `{z}/{x}/{y}` fill `TILEMATRIX/TILECOL/TILEROW`, unpadded zoom accepted (checked 2026-10-22) |
| Coverage | South Tyrol only (≈ 10.35-12.53 E, 46.17-47.15 N). The app requests them only inside that box and from zoom 10, over the standard OpenFreeMap style, which shows elsewhere |
| Auth | None. Capabilities: Fees and AccessConstraints "none" |
| Licence | Provincial geodata are CC0 by the Province's open-data policy; exceptions are stated per layer, none on these two. Credited in the map and on the About screen anyway |

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
