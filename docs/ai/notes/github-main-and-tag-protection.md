---
type: Reference
title: main and the release tags are protected by GitHub rulesets
description: Since 2026-10-31 two repository rulesets make main reachable only through a merged pull request (one approval, admins may merge their own) and let only admins, or the Release workflow after the owner approves its release environment, create, move or delete v* tags; release.py also refuses a commit that is not on origin/main.
tags: [github, release, git, workflow]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-31T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-31T12:00:00Z"
status: stable
sources:
  - id: rulesets
    resource: https://github.com/ta-samuelece/SouthTyrolTransit/settings/rules
    title: Repository rulesets (admin only)
---

# What GitHub enforces

Two rulesets on `ta-samuelece/SouthTyrolTransit`, both active:[^rulesets]

| Ruleset | Target | Rules | Bypass |
| --- | --- | --- | --- |
| Protect main (id 26265098) | default branch | no deletion, no force push, pull request with 1 approval, stale approvals dismissed, review threads resolved | Repository admin, **pull requests only** |
| Protect release tags (id 26265099) | `refs/tags/v*` | no creation, update or deletion | Repository admin, always |

The repository also deletes a pull request's branch once it is merged.

In practice:

- **Nobody pushes to `main` directly**, the owner included: GitHub rejects the push. Every change, a
  version bump or a docs-only commit too, goes through a branch and a pull request. The owner may merge
  their own pull request without an approval; anyone else's needs one.
- **Only the owner can publish a release.** A GitHub release creates its `v*` tag, which the tag ruleset
  refuses to anyone but an admin - write access alone used to be enough to reach every phone through the
  updater.
- The **Tests and lint** job of the CI workflow is a required status check
  ([CI](./ci-and-local-checks.md)): a pull request merges only when the unit tests and lint pass on it.

# The release environment

`.github/workflows/release.yml` publishes from GitHub. Its job runs in the `release` environment, which
only deploys from `main` and has the owner as its required reviewer: anyone with write access can start
the workflow, but nothing builds or publishes until the owner approves. Five secrets exist only in that
environment, set by the owner by hand and readable by no other job:

- `TRANSIT_RELEASE_STORE_BASE64`, `TRANSIT_RELEASE_STORE_PASSWORD`, `TRANSIT_RELEASE_KEY_ALIAS`,
  `TRANSIT_RELEASE_KEY_PASSWORD` - the signing key. Secrets cannot be read back, so they are not a
  backup of the keystore.
- `TRANSIT_RELEASE_GITHUB_TOKEN` - the owner's fine-grained personal access token (this repository only,
  Contents: read and write), which creates the release. The workflow's own `GITHUB_TOKEN` cannot: it acts
  as the GitHub Actions app, which the tag ruleset refuses, and a ruleset in a personal repository cannot
  exempt that app (GitHub answers 422 "must be part of the ruleset source or owner organization").
  When the token expires, releases fail at "Restore signing key" until the owner sets a new one.

`tools/release.py` adds the matching local check: the commit must be an ancestor of `origin/main`, so a
release can no longer be cut from a pushed but unmerged branch.

# Changing it

The rulesets live in the GitHub settings, not in the repository; read them with
`gh api repos/ta-samuelece/SouthTyrolTransit/rulesets`. Any change is a change on GitHub and needs the
user's approval (CLAUDE.md rule 12). Renaming the CI job breaks its required check unless the ruleset
is updated with it. The environment is read with `gh api repos/ta-samuelece/SouthTyrolTransit/environments/release`.

# What was checked

On 2026-10-31: `gh api repos/.../rules/branches/main` lists deletion, non_fast_forward and pull_request
from ruleset 26265098; both rulesets report the bypass modes above; `deleteBranchOnMerge` is true.

[^rulesets]: Created 2026-10-31 through the GitHub REST API (`POST /repos/{owner}/{repo}/rulesets`).
