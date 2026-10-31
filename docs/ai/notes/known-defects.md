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

# Open defects

1. **An installed in-app update leaves its APK in the cache.** `UpdateManager.download` writes the APK
   to `cacheDir/updates` and only clears that folder when the *next* download starts, so after Android
   installs the update the file (~48 MB for 0.1.6) stays on the phone until the following update. Fix:
   delete, at startup, every file there that is not a download newer than the running version. See
   [in-app updater internals](./in-app-updater-internals.md).
