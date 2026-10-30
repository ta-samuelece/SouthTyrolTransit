---
type: Status
title: In-flight work
description: What is in flight on the working branch right now and recorded nowhere else. Empty is the normal state.
tags: [handover, status]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-30T12:00:00Z"
status: stable
---

Only what is in flight **and** recorded nowhere else. What changed is git history and [log.md](./log.md);
what is durable belongs in [notes](./notes/index.md) and [decisions](./decisions/index.md); a bug found
along the way goes into [known defects](./notes/known-defects.md), not into this file. Empty is the normal state, and the
file has a 60-line budget that `check_docs_sync.py` enforces.

# Branch `ccr-20ab2d5c-tm26ov`: saved places (Home, Work, own places with icons)

Written in a cloud session without the Android SDK, so it has **never been built, tested or linted**.
Before merging: build once, which writes the user database's `2.json` schema next to `1.json`
(commit it), then run the unit tests (`SavedPlacesTest` is new) and `./gradlew lintDebug`, and try the
Plan row, the search sheet's star, the save sheet and the Saved screen's menu on a device. Clear this
section once that is done.
