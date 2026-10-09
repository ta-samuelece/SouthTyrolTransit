# .claude/scripts - shared helper scripts

Committed to git. Scripts that turned out to be worth keeping - typically documentation validators and
extraction helpers written during a task and then generalized.

If a script is worth running twice, it belongs here, with a docstring saying what it does and how to
invoke it. If it is genuinely single-use, keep it in `.claude/local/` (gitignored) and let it disappear.
Scripts that are part of building or releasing the app itself belong in `tools/`, not here.

## Conventions

- Python 3 (the repo's `tools/` already need it), **standard library only**: these scripts must run on a
  bare interpreter on Windows, macOS and Linux.
- Take paths as arguments; default to the repo root resolved relative to the script, never a hardcoded
  `C:\Users\...` path.
- Dry-run by default (`--apply` to write) for anything that edits files in bulk.

## Existing

- `check_docs_sync.py` - two jobs. It reports what changed since `CLAUDE.md` and `docs/ai/` were last
  updated (structural commits, other commits, uncommitted work, the age of the OKF specification
  review), and it checks the documentation against itself: duplicated top-level headings, backticked
  paths that do not exist, links that do not resolve, notes or decisions missing from their index, and
  `status.md` over its line budget. Read-only; exits non-zero on those, but never on structural drift
  alone - the docs commit follows the code commit, so failing there would deadlock it. Runs at session
  start *and* as a `PreToolUse` hook that blocks `git commit` - both configured in `settings.json`;
  `--hook` emits the JSON the session-start hook consumes; on a fresh session (or after `/clear`) it also
  lists the numbered entries of `docs/ai/notes/known-defects.md` and tells the agent to ask whether they
  should move to GitHub issues (CLAUDE.md rule 12). Add a deleted top-level directory to `REMOVED_ROOTS`
  so references to it start failing.
- `check_okf.py` - validates `docs/ai/` against the Open Knowledge Format spec. Read-only; fails only on
  real conformance violations, and reports missing recommended fields, broken links and list-shaped
  `generated`/`verified` blocks as advice.
