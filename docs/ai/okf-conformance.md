---
type: Reference
title: OKF conformance baseline
description: Which Open Knowledge Format version this bundle targets, when the specification was last reviewed, and what to re-check when it moves.
tags: [meta, okf, conformance]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
status: stable
sources:
  - resource: https://github.com/GoogleCloudPlatform/open-knowledge-format
    title: Open Knowledge Format - canonical repository (SPEC.md read at commit ad30107, 2026-08-21)
  - resource: https://cloud.google.com/blog/products/data-analytics/how-the-open-knowledge-format-can-improve-data-sharing
    title: Google Cloud blog announcement (describes v0.1)
---

This bundle targets **OKF v0.2**, declared as `okf_version: "0.2"` in the bundle-root `index.md` - the
only place the spec allows that key.

The `verified.at` date in this document's frontmatter is the last time the specification itself was read
and compared against this bundle. `check_docs_sync.py` reports when that date gets old (90 days).

# Where the specification lives

The canonical home is
[GoogleCloudPlatform/open-knowledge-format](https://github.com/GoogleCloudPlatform/open-knowledge-format),
file `SPEC.md`.

Two stale sources to be careful of:

- The Google Cloud **blog announcement** describes **v0.1** (a `timestamp` field, a body citations list)
  and is no longer accurate.
- The original `GoogleCloudPlatform/knowledge-catalog/okf` directory is a **frozen snapshot**.

# What v0.2 asks of us, and what we do

| Spec point | This bundle |
| --- | --- |
| Non-empty `type` on every non-reserved document (the only hard requirement) | Enforced by `check_okf.py`; vocabulary in `conventions.md` |
| `index.md` and `log.md` reserved, never concept documents | Enforced by `check_okf.py` |
| `okf_version` only in the bundle-root `index.md` | Enforced by `check_okf.py` |
| `log.md` date headings in `YYYY-MM-DD` form, newest first | Reported as advice by `check_okf.py` |
| Recommended `title`, `description`, `resource`, `tags` | All present except `resource`, which we omit: our concepts describe repository behaviour, not an addressable asset |
| Trust family `generated` / `verified` / `status` / `sources` | Used, with agent actors as `claude-code/<model>` and people as `human:<id>` |
| Timestamps ISO 8601 with an explicit offset | Reported as advice by `check_okf.py` |
| Links may be bundle-absolute or relative | We use **relative** - this bundle sits inside a larger repository, and relative links also resolve on GitHub |
| Consumers must tolerate broken links, unknown types, missing optional fields | `check_okf.py` reports these as advice, never as failure |

Changes v0.2 made to v0.1, so nobody reintroduces the old shapes: `timestamp` became
`generated: { by, at }`, and a body `# Citations` list became the `sources` frontmatter key with
per-claim footnotes keyed by `sources[].id`.

# When the specification moves

Do not silently migrate. Read the new `SPEC.md`, then **propose** the change to the user:

1. Diff the new spec against the table above and list what actually affects this bundle.
2. Say what would have to be rewritten or reorganised, and what it buys - a version bump with no
   practical effect on us is worth saying out loud too.
3. On approval: apply the changes, bump `okf_version` in the bundle-root `index.md`, update this table,
   re-verify this document (new `verified.at`), add a `log.md` entry, and commit it as AI material.

Unused parts of the spec worth revisiting if our needs change: `stale_after` for facts with a known expiry
(for example an API outage workaround), `Attested Computation` documents for the checker scripts, and
`resource` URIs if concepts ever describe addressable assets such as individual API endpoints.
