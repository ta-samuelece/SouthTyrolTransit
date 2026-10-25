---
type: Finding
title: Known defects - open bugs found in the code and not yet fixed
description: The register of open bugs and documentation errors, raised with the user at every session start (keep here or move to GitHub issues); an entry is deleted in the same change that fixes or moves it.
tags: [defects, bugs, backlog]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T22:00:00Z"
status: stable
---

Bugs are tracked **here** unless the user decides to move them to GitHub issues: at the start of every
session the hook lists these entries and the agent asks (CLAUDE.md rule 12). One entry per defect,
numbered, title in bold - the hook reads that shape. What goes wrong, where, and the likely fix. When a
fix lands, or an entry moves to a GitHub issue, delete it in the same change - this is a list of what is
open, not a history (git has that). When you find a new defect while working, add it here and tell the
user. Background for each lives in the linked note.

Open bugs that the user moved to GitHub issues are tracked there (labels `bug` and `documentation`) and
no longer listed here - read the issue before working on one, and reference it in the fixing commit
(CLAUDE.md rule 3).

1. **Journey card duration wraps into the arrival time.** In `JourneyCard`
   (`app/src/main/kotlin/org/southtyrol/transit/feature/journey/ResultsScreen.kt`) the times row is
   departure, dash, arrival, a weighted spacer and the duration, all in one `Row`. With 12-hour times
   (en-US) and an hour-long trip ("11:12 PM - 12:14 AM", "1 h 02 min") the row overflows: the
   duration text squeezes to its minimum width and wraps onto two lines, overlapping the "AM" of the
   arrival. Seen on a 1080x2400 / 420 dpi emulator on 2026-10-25. Likely fix: keep the duration on one
   line (`maxLines = 1`, `softWrap = false`) and let it move below the times when the row is too
   narrow, or shrink the time style for long values.
