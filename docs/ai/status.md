---
type: Status
title: In-flight work
description: What is in flight on the working branch right now and recorded nowhere else. Empty is the normal state.
tags: [handover, status]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-03T12:00:00Z"
status: stable
---

Only what is in flight **and** recorded nowhere else. What changed is git history and [log.md](./log.md);
what is durable belongs in [notes](./notes/index.md) and [decisions](./decisions/index.md); a bug found
along the way goes into [known defects](./notes/known-defects.md), not into this file. Empty is the normal state, and the
file has a 60-line budget that `check_docs_sync.py` enforces.

# Saved places: built and tested, not tried on a device

Saved places (Home, Work, own places with icons) and the place-card row were written in cloud sessions
without the Android SDK. Since 2026-10-31 (branch `feature/place-card-menu`) they build, the unit tests and
`./gradlew lintDebug` pass, and both databases' `2.json` schemas are committed. On 2026-11-03 the save
sheet (Home and Work from Add place) and the equal card heights were seen on the API 36 emulator. Still
**not tried on a device**: the Plan row (tap plans from the current location, long press opens the Arrive
here / Start from here pills, sideways scroll), the search sheet's star and the Saved screen's menu. Clear
this section once that is done.

# Alerts top lip: builds, unit tests and lint not run

The Service alerts lip (dashboard cards, search in the lip, "In force now" / "Coming up") and the new
`TopLip` slots (`lipContent`, `modal`) were written on 2026-11-03 in a cloud session without the Android
SDK. Later that day `assembleDebug` built them, and the cards, their one-line form while scrolling and
Plan's lip looked right on the API 36 emulator. Still to do: run the unit tests and `./gradlew lintDebug`,
then try the card toggles, search focus and keyboard, Back and a line badge opening search. Clear this
section once that is done.
