---
type: Reference
title: Following a journey live - the service, the step logic and what it cannot know
description: A started journey lives only in memory (ActiveJourney) and is lost when Android ends the app; a foreground service (location or specialUse type) runs FollowEngine, which applies matched runs' live times to the plan, computes the step and off-plan state in core/model, and feeds the screen and the notification; planner delays are frozen at planning time; the notification channel must be default importance to show on the lock screen, and a swiped-away notification is posted again because Android 14+ allows no non-dismissable one; the Android 16 Live Update needs POST_PROMOTED_NOTIFICATIONS.
tags: [journey, follow, service, notification, location, realtime]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-17T16:00:00Z"
status: stable
---

Issue #23: "Start" on the journey detail screen follows that journey until arrival, with an ongoing
notification. Built on 2026-10-17; not yet confirmed on a device.

# Who owns what

- `ActiveJourney` (`app/.../follow/ActiveJourney.kt`) is an in-memory `@Singleton` with the
  `FollowSession` (journey, the RequestCodec request it was planned from, location on or off) and the
  latest `FollowView`. **Nothing is persisted, by decision**: when Android ends the process the journey is
  gone, and the service is `START_NOT_STICKY`. Starting another journey replaces the first.
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
  matched, reads location every 10 s / 20 m when allowed, and recomputes every 15 s and on every new
  snapshot or fix. It stops the journey itself 2 min after arrival (`JourneyFollow.finished`).
- The screen (`FollowScreen`, route `FollowRoute`, no arguments) and the notification only read
  `FollowView`; so does the **journey strip** (`feature/follow/JourneyStrip.kt`), which `TransitApp` draws
  under the `NavigationSuiteScaffold` on every screen but `FollowRoute` and the map search, and which opens
  it. `FollowText` holds the shared wording (`title`, `text`, `strip`) so all three say the same. While
  the strip shows, the scaffold consumes the bottom `navigationBars` inset (the bar stops padding for the
  gesture area; the strip's spacer takes it) and is clipped to 24 dp bottom corners over the strip's
  colour, as in the design (2026-10-18). The strip replaced a "Journey in progress" item on the Plan tab.

# The logic, in core/model

`JourneyFollow.kt`, tested by `JourneyFollowTest`:

- `LiveJourney.apply` copies a merged run's predictions and cancellation onto its planner leg (boarding
  call by `Approaches.boardingIndex`, alighting = the first later call at the leg's destination station).
  Without a matched run the leg keeps the **planner's times as of planning** - EFA delays are not
  refreshed while following. A walk after a late leg is moved to start when that leg arrives (same
  length); without this, a late last bus followed by a walk ended following while the user was still on
  board.
- Freshness is that of the step's own leg (for a walk, the leg it leads to): Live when its merged run
  has a GTFS-RT prediction; else, when the planner reported live times for the leg, Live for 5 minutes
  after the start (`TRIP_UPDATE_MAX_AGE`), then Not current; else the run's (Scheduled). A matched run
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

Unit tests (`JourneyFollowTest`, 13 cases), the full unit-test run and `lintDebug` on 2026-10-17. Not run
on a device: the Live Update chip, the location service type on Android 14+, and permission denials.
