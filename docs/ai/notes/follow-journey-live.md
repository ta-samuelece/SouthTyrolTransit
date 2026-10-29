---
type: Reference
title: Following a journey live - the service, the step logic and what it cannot know
description: A started journey lives only in memory (ActiveJourney) and is lost when Android ends the app; a foreground service (location or specialUse type) runs FollowEngine, which applies matched runs' live times to the plan, computes the step and off-plan state in core/model, and feeds the screen and the notification; the planner's delays are re-fetched every 30 s through each leg's liveRef; the notification channel must be default importance to show on the lock screen, and a swiped-away notification is posted again because Android 14+ allows no non-dismissable one; the Android 16 Live Update needs POST_PROMOTED_NOTIFICATIONS.
tags: [journey, follow, service, notification, location, realtime]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-19T16:30:00Z"
status: stable
---

Issue #23: "Start" on the journey detail screen follows that journey until arrival, with an ongoing
notification. Built on 2026-10-17; not yet confirmed on a device.

# Who owns what

- `ActiveJourney` (`app/.../follow/ActiveJourney.kt`) is an in-memory `@Singleton` with the
  `FollowSession` (journey, the RequestCodec request it was planned from, location on or off) and the
  latest `FollowView`. **Nothing is persisted, by decision**: when Android ends the process the journey is
  gone, and the service is `START_NOT_STICKY`. Starting another journey, or a single ride, replaces the first:
  since 2026-10-29 the slot is shared with [following a ride](./follow-ride.md), and the service
  collects `ActiveJourney.followed` (journey or ride) rather than the session.
- The Start sheet has "Use my location" on by default (user decision, 2026-10-18): Start then asks for
  the location permission if needed, and a refusal simply follows by time.
- `FollowController` is the UI's only entry: it sets the session and calls `startForegroundService`.
  Start always comes from a visible screen, so Android 12's background-start ban does not apply.
- `FollowJourneyService` collects the session with `collectLatest`: a new session cancels the old
  `FollowEngine.run`; a null session stops the service. Every `startForegroundService` is answered with
  `startForeground` (a placeholder notification when the journey was stopped meanwhile), or Android
  crashes the app; only the Stop action, a plain `getService` start, skips it. Type `location`
  only when the user turned location on and the permission is held, else `specialUse` (Android 14+) or
  none; a refused type falls back to the other once.
- `FollowEngine` matches legs to timetable runs with `TripRepository.forJourney` (retrying every 2 min
  until one matches - the timetable may still be downloading), polls realtime every 30 s while a run is
  matched, fetches the journey planner's live times (`TripRepository.liveTrip`, the trip screen's
  stop-sequence request) every 30 s for each transit leg not yet arrived, reads location every 10 s /
  20 m when allowed, and recomputes every 15 s and on every new
  snapshot or fix. It stops the journey itself 2 min after arrival (`JourneyFollow.finished`).
- The screen (`FollowScreen`, route `FollowRoute`, no arguments) and the notification only read
  `FollowView`; so does the **journey strip** (`feature/follow/JourneyStrip.kt`), which `TransitApp` draws
  under the `NavigationSuiteScaffold` on every screen but `FollowRoute` and the map search, and which opens
  it. `FollowText` holds the shared wording (`title`, `text`, `strip`) so all three say the same. While
  the strip shows, the scaffold consumes the bottom `navigationBars` inset (the bar stops padding for the
  gesture area; the strip's spacer takes it) and is clipped to 24 dp bottom corners over the strip's
  colour, as in the design (2026-10-18). The strip is dark in every theme - the inverse surface in the
  light theme, black (#000000) in the dark and pure-black themes, at the user's request - so it always uses
  the dark theme's status colours; error red when off plan. It replaced a "Journey in progress" item on
  the Plan tab.

# The logic, in core/model

`JourneyFollow.kt`, tested by `JourneyFollowTest`:

- **Planner delays are refreshed** (2026-10-19): they used to be frozen at planning time, so a bus
  GTFS-RT had nothing for showed its delay from the moment of Start and turned "Not current" after
  5 minutes (seen on a device, line 111). Each EFA trip leg now carries `Leg.liveRef`, built by
  `EfaXml.leg` in the board's format (see [identifiers](./identifiers-across-sources.md)).
  `LiveJourney.applyPlanner` replaces the planned delays with the latest fetch (boarding by station and
  planned departure within 2 min, alighting = the first later call at the destination station, null when
  neither end has a delay). The latest fetch is applied however old - a failed or empty fetch keeps the
  last one, so a leg never falls back to its planning-time delay - and only its freshness ages.
- `LiveJourney.apply` then copies a merged run's GTFS-RT predictions and cancellation onto that leg
  (boarding call by `Approaches.boardingIndex`, alighting = the first later call at the leg's destination
  station); a call without a prediction keeps the planner's time. A walk after a late leg is moved to start when that leg arrives (same
  length); without this, a late last bus followed by a walk ended following while the user was still on
  board.
- Freshness is that of the step's own leg (for a walk, the leg it leads to): Live when its merged run
  has a GTFS-RT prediction; else, when the planner reported live times for the leg, Live for 5 minutes
  (`TRIP_UPDATE_MAX_AGE`) after their last applied fetch - or after the start, before any - then Not
  current, with "updated ... ago" counting from the same moment; else the run's (Scheduled). A matched run
  **without** a prediction must not win over the planner's delay: it did at first, and a leg showing
  "+2" was labelled Scheduled (seen on the emulator, 2026-10-18). `FollowView.live` (Live and a delay
  behind the step) is the one rule for drawing a time live, used by the hero, the timeline, the strip
  and the notification. Minutes in the notification round down like `Format.countdown`, so the
  notification and the screen agree.
- `step`: the first leg not yet arrived decides - a walk (counting down to the next boarding), BOARD
  before a transit leg leaves, RIDE (with `change` when another transit leg follows), ARRIVED.
- `offPlan`, in order: a cancelled leg still ahead; a connection that can no longer be made (previous
  arrival plus the walks between after the departure); with location only, still within 150 m of the
  boarding stop 2 min after departure (missed boarding), or more than 800 m from the leg's route while
  riding. Fixes less accurate than 100 m are ignored, and the engine publishes a location finding only
  after it has held for 30 s, so a train dwelling at the platform is not reported as missed.

"Find another way" opens the planner results from the position (or the stop where the plan broke) to the
same destination, leaving now. The user starts the new journey themselves.

# The notification

Channel `journey`, id 2: **IMPORTANCE_DEFAULT with no sound or vibration**, lock-screen visibility public.
The first version used IMPORTANCE_LOW, which Android counts as silent and keeps off the lock screen by
default; importance cannot be raised on an existing channel, so the channel got a new id and
`createChannels` deletes the old `follow`. **It cannot be made non-dismissable**: since Android 14 the
system lets the user swipe away any ongoing notification except media, calls and a few exempt packages
(`canBeNonDismissible` in `NotificationManagerService`), so a delete intent (`ACTION_DISMISSED`) makes
the service post it again at once while the journey runs. Title and text from `FollowText`, a Stop action
(`PendingIntent.getService` with `ACTION_STOP`) and a tap that opens `FollowRoute` through
`MainActivity.openFollowIntent` (the same pattern as the widget's stop intent). The small icon is the
step's own (`ic_mode_*`, `ic_follow_change`, `ic_follow_arrived`), because Android 16 shows it in the
status bar chip; the notification colour is the mode's. Those drawables are generated from the same
Material icons the app uses (`TransportMode.icon()`, the ropeway symbols in `RopewayIcons`): Compose
`ImageVector`s cannot be notification icons. The service re-checks
every 5 s and posts only when the words, chip, freshness or progress percent changed.

On Android 16+ (`SDK_INT >= 36`) it is a Live Update: `NotificationCompat.ProgressStyle` with a segment
per transit leg in its mode colour and one grey stretch for the walking and waiting between; the
system's gap between segments and the change of colour mark the junctions. **No points, by decision
(2026-10-18)**: the platform draws a point as a rounded square only - `NotificationProgressBar` always
builds `new Point(null, color, ...)`, so no app can give it an icon or a ring, and the user preferred no
points to boxes. Start and end icons were tried and dropped as noise. The only icon on the bar is the
tracker: the current step's mode, white on a circle of its colour (drawn to a bitmap). A bar drawn like
the design (rings at junctions) would need a custom layout, which Android refuses to promote: no chip.
Plus
`setRequestPromotedOngoing` and `setShortCriticalText` (the chip, "6 min"). Promotion also needs the
`POST_PROMOTED_NOTIFICATIONS` permission and a channel above MIN; the user can still turn it off. Below
16 the same notification uses BigTextStyle and a plain progress bar.

# What was checked

The screen shows the step card, then every leg of the itinerary with the journey detail's own
`TransitLeg`, `WalkLeg` and `TransferRow` (done legs dimmed); the current transit leg is open. Every leg's
stops are `LegStops` (`feature/common/StopTimeline.kt`): the trip screen's own `StopTimelineRow`s, so a
leg looks like an opened bus trip. Its map is
fitted to the current step's leg (`rememberJourneyMap(focus = ...)`) and re-fits when the step moves on.

Unit tests (`JourneyFollowTest`, 13 cases), the full unit-test run and `lintDebug` on 2026-10-17. The
planner refresh (2026-10-19): `core:model` tests only (`JourneyFollowTest`, 14 cases), in a container
without the Android SDK - the parser test, `app` and `lintDebug` were not run, and the refresh has not
been seen on a device or against the live EFA. Not run
on a device: the Live Update chip, the location service type on Android 14+, and permission denials.
