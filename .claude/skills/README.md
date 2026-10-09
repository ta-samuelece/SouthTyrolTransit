# .claude/skills - shared runbooks

Committed to git. Each subfolder is a **skill**: a procedure Claude Code can load and follow on demand,
in any clone of this repo, for any user. Claude sees only each skill's `name` + `description` until it
decides one is relevant, so they cost almost nothing to keep around - unlike `CLAUDE.md`, which is loaded
in full on every session.

```
.claude/skills/<skill-name>/SKILL.md
```

```markdown
---
name: <kebab-case-name>
description: What it does AND when to use it. This single line is the only thing Claude
  matches against, so name the trigger words a user would actually type.
---

# Title

Steps, commands, gotchas. Reference real paths and real commands.
```

Supporting files (scripts, templates, longer reference) can sit alongside `SKILL.md` in the same folder
and be linked from it - they are read only if the skill is invoked.

## What makes a good skill here

A procedure that is **repeated**, **multi-step**, and **easy to get subtly wrong** - committing,
publishing a release, regenerating icons after adding one. One-off explanations belong in
`docs/ai/notes/` instead.

## Existing

- `commit-changes` - the approval-gated commit procedure, the code/AI-material split, and what makes
  pushing to `main` different from an ordinary feature-branch push. The rules in `CLAUDE.md` are
  mandatory, this is the how.
- `publish-release` - bumping the version, building the signed APK and publishing a stable or preview
  GitHub release with `tools/release.py`, with an approval gate before anything reaches users' phones.
