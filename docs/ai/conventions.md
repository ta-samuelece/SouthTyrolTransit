---
type: Convention
title: How to write in this knowledge base
description: What belongs in the bundle, the type vocabulary, and the OKF rules every document here follows.
tags: [meta, okf, documentation]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
status: stable
---

# What belongs here

- **Here** - anything a *different* person, or a *future* session on a *different* machine, would need in
  order not to repeat an investigation. If it cost more than a few minutes to learn, write it down.
- **`/CLAUDE.md`** - stable project-wide facts and the mandatory working rules. Keep it short: it is
  loaded on every single session and competes for room with actual work.
- **`/README.md` and `/docs/*.md`** - human documentation: features, architecture, modules, data sources,
  update strategies, API limitations, the release process. Not AI-specific, and not to be duplicated
  here - link to them instead of restating them. A note here records what they *don't* say, or where the
  code has moved past them.
- **`/docs/fixtures/`** - captured real API payloads the tests read. Data, not knowledge.
- **`.claude/local/`** (gitignored) - scratch, drafts, one-off output. Nothing durable.
- **Claude Code's own memory store** (outside the repo, per-machine) - personal preferences and working
  style. Not shared, by design.

# Open Knowledge Format

This bundle follows [OKF v0.2](https://github.com/GoogleCloudPlatform/open-knowledge-format). The rules
that matter in practice:

- Every `.md` file except the reserved ones starts with YAML frontmatter containing a non-empty `type`.
  That is the only hard requirement; everything else is there because it is useful.
- `index.md` and `log.md` are **reserved**. `index.md` lists what is in a directory, `log.md` is dated
  history. Neither is a concept document, so neither carries a `type`.
- Only the bundle-root `index.md` declares `okf_version`.
- Links between documents are **relative** (`./other.md`, `../status.md`). OKF recommends bundle-root
  absolute links, but this bundle lives inside a larger repository, and relative links are the form that
  also resolves correctly on GitHub.

# Type vocabulary

`type` is a free string in OKF. Within this bundle, use one of:

| `type` | For |
| --- | --- |
| `Finding` | Something investigated and established, with evidence. Most notes. |
| `Reference` | A standing fact about the repository that is simply true until it changes. |
| `Decision` | An ADR-style record: context, decision, consequences. |
| `Status` | The in-flight handover note. There is exactly one. |
| `Convention` | Rules for working in this bundle. This document. |

# Writing an entry

- `title` and `description` are what an agent sees before deciding to open the file. Make the description
  a single sentence that stands alone, and copy it into the directory's `index.md` entry.
- `tags` are for filtering; reuse existing tags rather than inventing near-duplicates.
- `generated: { by, at }` records who produced the document. Actors are `claude-code/<model>` for agents,
  `human:<id>` for people (the GitHub username), `process:<id>` for automation.
- `verified` is a stronger claim than `generated`: only add it for something actually checked against
  reality - the code, a test run, a live API - and say what was checked in the body. It takes the same
  `{ by, at }` mapping as `generated` - one shape, so the two cannot drift (`check_okf.py` flags a list) -
  and its date moves when the document is re-checked, not when it is merely edited. A document verified
  by a `human:` actor counts as human-reviewed; by an agent, machine-confirmed.
- `sources` lists what the content was derived from - commits, source files, other documentation. Each
  entry needs a `resource`.
- `status` is `stable` unless the entry is a `draft` or has been superseded (`deprecated`).

# House rules

- **English only**, always. Chat can happen in any language; everything written here is English.
- Absolute dates (`2026-10-09`), never "last week".
- Link to commits by hash where the detail lives in the diff - do not re-describe the diff.
- Name code by path and symbol (`LiveOverlay` in `core/model/.../Realtime.kt`), not by line number: line
  numbers rot with every edit.
- Prune aggressively. A stale note is worse than a missing one, because it will be trusted.
- **Replace, never append.** When a finding changes, rewrite the paragraph that stated it. Two paragraphs
  disagreeing is worse than either alone; `check_docs_sync.py` catches a duplicated heading, not a
  contradiction.
- Add a `log.md` entry only when the bundle's structure or conventions change - not per finding (the note
  is the record) and never for work done (that is git history).
- `status.md` holds only what is in flight and recorded nowhere else, within a 60-line budget.

Validate the bundle with `python .claude/scripts/check_okf.py`.
