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
`./gradlew lintDebug` pass, and both databases' `2.json` schemas are committed. Still **not tried on a
device**: the Plan row (tap plans from the current location, long press opens the Arrive here / Start
from here pills, equal card heights, sideways scroll), the search sheet's star, the save sheet and the
Saved screen's menu. Clear this section once that is done.

# Alerts top lip: written without the Android SDK, not built

The Service alerts lip (dashboard cards, search in the lip, "In force now" / "Coming up") and the new
`TopLip` slots (`lipContent`, `modal`) were written on 2026-11-03 in a cloud session that could not reach
the Android SDK, so nothing was compiled: run the unit tests and `./gradlew lintDebug`, then try it on a
device (card toggles, search focus and keyboard, Back, a line badge opening search, Plan's lip unchanged).
Clear this section once that is done.
