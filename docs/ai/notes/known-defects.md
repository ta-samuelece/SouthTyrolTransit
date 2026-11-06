---
type: Finding
title: Known defects - open bugs found in the code and not yet fixed
description: The register of open bugs and documentation errors, raised with the user at every session start (keep here or move to GitHub issues); an entry is deleted in the same change that fixes or moves it.
tags: [defects, bugs, backlog]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-31T10:00:00Z"
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

1. **Stop board title differs from the search result's name.** Search can show "Bahnhof Meran Untermais", but
   the board then titles itself "Merano, Stazione di Merano Maia Bassa": `StopViewModel` replaces the name it
   was opened with by the timetable's, which has no German name for this station. Likely fix: keep the
   opening name, or give boards a name source that follows the app's language. See
   [identifiers](./identifiers-across-sources.md).
2. **"No matches" flashes while typing in a search.** `MapSearchViewModel` and `PlacesViewModel` set `loading`
   only after the debounce, so a new query shows the no-results text for up to 300 ms before its search starts.
   Likely fix: treat a query that has not been searched yet as loading.
