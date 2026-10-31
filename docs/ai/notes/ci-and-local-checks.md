---
type: Finding
title: CI runs the unit tests and lint on pull requests; everything else still only runs locally
description: Since 2026-10-31 a GitHub Actions workflow runs the unit tests and lintDebug on every pull request into main and every push to main, and is a required check for merging; it never builds or publishes a release, and an earlier workflow that did was deleted on 2026-10-05.
tags: [ci, build, testing, release, github]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-31T12:00:00Z"
status: stable
sources:
  - id: history
    resource: https://github.com/ta-samuelece/SouthTyrolTransit/commits/main
    title: Commits 2c47914, 42590f1 (created), bdc8a80, 6278736 (deleted), all 2026-10-05
---

# The state today

`.github/workflows/ci.yml` runs one job, **Tests and lint**, on every pull request into `main` and every
push to `main`: JDK 21 (Temurin), the Android platform 37.1 installed with `sdkmanager`, then
`:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest` and `lintDebug`. On failure it
uploads the test and lint reports as an artifact for seven days. It has read-only permissions and no
secrets.

The job is a required status check in the "Protect main" ruleset
([branch protection](./github-main-and-tag-protection.md)), so a pull request cannot be merged while it
is red. Its name is the check's identity: renaming the job silently breaks the requirement - rename the
check in the ruleset in the same change.

What CI does **not** do:

- The opt-in network tests (`-PliveTests=true`, `-PgtfsReal`) never run there
  ([test suite](./test-suite-and-opt-in-live-tests.md)).
- No release build: R8 and the release signing are only exercised by a local `assembleRelease`.
- No release. Publishing is the separate, manually started Release workflow (or `tools/release.py` on
  the owner's machine), gated by the owner's approval
  ([branch protection](./github-main-and-tag-protection.md)). `release.py` checks the version, signature,
  clean tree, that the commit is on `origin/main`, and the tag - not whether the code works.

Consequences for agents (CLAUDE.md rules 2 and 9): CI only reports after a push, so still run the module
tests and `./gradlew lintDebug` yourself before reporting a change as done, and say which ones you ran.
Lint has `abortOnError = true`, so a lint error is a real failure, locally and in CI.

# The workflow that was removed

On 2026-10-05 an "Android CI/CD" workflow was added (first at workflows/android.yml by mistake, then at
.github/workflows/android.yml) and both copies were deleted the same evening.[^history] No reason is
recorded. Its design is why the current one publishes nothing:

| What it did | Why that would break today |
| --- | --- |
| `setup-java` 17 | The build needs JDK 21+ (README section 8) |
| `assembleDebug` only, no tests or lint | Checks nothing beyond compilation |
| `-PversionCode=<run number>` / `-PversionName` | `app/build.gradle.kts` does not read these properties; the version lives in the file |
| On a `v*` tag, published the **debug** APK as a GitHub release | `tools/release.py` creates the `v*` tags itself; the updater would offer a debug-signed APK that fails its same-certificate check |

# What was checked

`git log --all -- .github workflows` and the deleted file's content at `42590f1`, on 2026-10-09. The new
workflow has not run yet when this was written; see the pull request that adds it.

[^history]: Commits 2c47914, 42590f1, bdc8a80, 6278736
