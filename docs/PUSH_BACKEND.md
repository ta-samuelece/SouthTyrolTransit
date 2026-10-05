# Optional realtime push backend: contract

A pure Android client cannot deliver near-instant disruption notifications. WorkManager periodic
work runs at most every 15 minutes, and Doze / App Standby buckets can defer it much longer. The
shipped app is therefore honest:

- **Local mode (implemented).** `AlertCheckWorker` runs about every 30 minutes, only when enabled. It needs a network connection and a battery that is not low. It refreshes GTFS-RT and EFA alerts and notifies once per new alert that affects a saved stop or line. The settings text says it is not instant.
- **Push mode (not implemented, reference contract below).** A small server watches the feeds continuously and sends Firebase Cloud Messaging (FCM) messages. The client side is abstracted by `org.southtyrol.transit.model.PushBackend`. The default binding is `NoPushBackend`, so the app has no Firebase dependency until a backend exists.

## Server responsibilities

1. Poll `trip-updates.pb`, `service-alerts.pb` (GTFS-RT, see `API_DISCOVERY.md`) every 20–30 s, and EFA `XML_ADDINFO_REQUEST` every 5–10 min. Respect upstream caching. Use one shared poller for all users.
2. Maintain subscriptions: `token → {routes, stationKeys, journeys, language}`.
3. Diff alerts by id and normalized text (same rules as `AlertDeduplicator`). For trip-level monitoring, compare predicted vs scheduled times of subscribed planned journeys.
4. Send FCM *data* messages with high priority only for user-visible changes, rate-limited per token.

## HTTP API (JSON over HTTPS)

```
POST /v1/subscriptions
{ "token": "<fcm token>", "language": "de",
  "routes": ["1-201-26a-5"], "lines": ["BUS:201"], "stations": ["it:22021:468"],
  "journeys": [{ "legs": [{ "tripId": "403.TA.1-201-26a-5.5.H", "serviceDate": "2026-10-05", "fromStopSequence": 3 }] }] }
→ 201 { "id": "<subscription id>" }

DELETE /v1/subscriptions/{token}        → 204
```

## FCM data payload

```
{ "type": "alert" | "delay" | "cancellation",
  "alertId": "efa:14818_STA",
  "title": "<localized header>", "body": "<localized text>",
  "station": "it:22021:468", "line": "BUS:201", "tripId": "...", "serviceDate": "2026-10-05",
  "observedAt": 1791134100 }
```

The client displays the payload with the existing `CHANNEL_ALERTS` channel and deep-links to the stop, line or trip.

## Privacy

- Store only the FCM token and the subscribed entity ids. Never store locations.
- Tokens expire after 60 days without renewal.
- Provide a delete endpoint, which the client calls when notifications are disabled.
