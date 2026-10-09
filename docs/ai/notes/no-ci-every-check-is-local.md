---
type: Finding
title: There is no CI - tests, lint and the release checks only run when someone runs them
description: A GitHub Actions workflow existed for a few minutes on 2026-10-05 and was deleted; it built a debug APK with Java 17, ran no tests, and would have published debug-signed releases on every v* tag.
tags: [ci, build, testing, release, github]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-09T12:00:00Z"
status: stable
sources:
  - id: history
    resource: https://github.com/ta-samuelece/SouthTyrolTransit/commits/main
    title: Commits 2c47914, 42590f1 (created), bdc8a80, 6278736 (deleted), all 2026-10-05
---

# The state today

The repository has no `.github/` directory. Nothing runs on a push or a pull request: not the unit tests,
not Android Lint, not a build. The only automated gate anywhere is `tools/release.py`, which checks the
version, signature, clean tree and tag - not whether the code works.

Consequences for agents (CLAUDE.md rules 2 and 9):

- Run the module tests and `./gradlew lintDebug` yourself before reporting a change as done, and say
  which ones you ran. Lint has `abortOnError = true`, so a lint error is a real failure.
- A push to `main` is unchecked, and `main` is what releases are cut from - hence its own approval.

# The workflow that was removed

On 2026-10-05 an "Android CI/CD" workflow was added (first at `workflows/android.yml` by mistake, then at
`.github/workflows/android.yml`) and both copies were deleted the same evening.[^history] No reason is
recorded. If CI is ever reintroduced, do not restore that file - it would conflict with how the app is
released now:

| What it did | Why that breaks today |
| --- | --- |
| `setup-java` 17 | The build needs JDK 21+ (README section 8) |
| `assembleDebug` only, no tests or lint | Checks nothing beyond compilation |
| `-PversionCode=<run number>` / `-PversionName` | `app/build.gradle.kts` does not read these properties; the version lives in the file |
| On a `v*` tag, published the **debug** APK as a GitHub release | `tools/release.py` creates the `v*` tags itself; the updater would offer a debug-signed APK that fails its same-certificate check |

A useful CI here would run `:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest` and
`lintDebug` on pull requests and pushes to `main`, and **never** publish a release. Note that compileSdk
37.1 must be available on the runner.

# What was checked

`git log --all -- .github workflows` and the deleted file's content at `42590f1`, on 2026-10-09.

[^history]: Commits 2c47914, 42590f1, bdc8a80, 6278736
