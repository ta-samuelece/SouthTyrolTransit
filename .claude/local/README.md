# .claude/local - personal scratch (not committed)

Everything in this folder is gitignored except this README. It is yours alone: temp files, one-shot
scripts, intermediate dumps, captured API payloads you are still looking at, half-written notes,
experiment output.

Nothing here survives a fresh clone, and nobody else sees it. When something in here turns out to matter:

| It is... | Move it to |
| --- | --- |
| a finding others would need | `docs/ai/notes/` (one file per finding) |
| a decision with consequences | `docs/ai/decisions/` |
| a procedure you'll repeat | `.claude/skills/<name>/SKILL.md` |
| a script worth running twice | `.claude/scripts/` |
| a real API payload a test should use | `docs/fixtures/` |

Your Claude Code preferences and working-style memory are **not** stored here - they live in Claude Code's
own per-machine store outside the repo, and stay personal by design.
