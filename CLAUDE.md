# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rules for AI agents - read first

These are not suggestions. They apply to every user of this repository, in every session.

**1. Never commit without explicit approval.** Even when the user says "commit for me", first open an
`AskUserQuestion` dialog showing the exact commit title and body, and wait for the answer. The user may
reply that they would rather batch more work into a later commit - that is a normal outcome, not a
blocker. Full procedure: the `commit-changes` skill.

**2. Never push to `main`, create a tag, or publish a release without a dedicated approval dialog.** There
is no CI (see [no CI](docs/ai/notes/no-ci-every-check-is-local.md)), so nothing stands between a push to
`main` and the commit every release is cut from. A GitHub release is the deploy: the app's in-app updater
offers it to every phone on the matching channel - stable releases to everyone, `-preview.N`
pre-releases to the Preview channel. You may work out and show the exact `git push`, `git tag` or
`python tools/release.py` command you intend to run, but you must never run it until the user has approved
it in its own `AskUserQuestion`. `python tools/release.py --dry-run` publishes nothing and is not gated.
Ordinary feature-branch pushes (anything other than `main`) are not gated by this rule. Full procedure:
the `publish-release` skill.

**3. Split commits by subject.** App and build changes (`app/`, `core/`, `gradle/`, `*.gradle.kts`,
`tools/`, `icons/`, `docs/` outside `docs/ai/`, `README.md`) go in one commit. Updates to AI-facing material
(`CLAUDE.md`, `docs/ai/`, `.claude/`) go in a separate commit. Two commits means two questions in the same
approval dialog - one per commit. Bugs are tracked in
[known defects](docs/ai/notes/known-defects.md), not in GitHub issues (rule 12): a commit that fixes one
deletes its entry in the same commit and names the bug in its own words. Only if a GitHub issue already
exists for the work, end the title with `(#<number>)` and add `Fixes #<number>` to the body when the
commit completes it - never invent a number.

**4. Keep commit messages short and high-level.** The title says what changed for the user of the app,
not which files moved: "Stop filters by direction and line, fix search reload loop". A version bump goes
at the end of the title (`...; 0.1.5-preview.1`) and in the body (`Version 0.1.5-preview.1 (versionCode
6)`). A body, when needed, is a short bullet list.

**5. English only in anything written to the repository.** Chat may happen in whichever language the user
prefers, and you should reply in that language. But documentation, runbooks, memories, READMEs, commit
messages, code comments, and identifiers are always in English. App strings are the exception by nature:
they live in `values/`, `values-de/` and `values-it/` and must exist in all three (rule 9).

**6. Keep the AI material in sync with reality. This one is enforced, not trusted.**
This repo only works for agents while `CLAUDE.md` and `docs/ai/` describe what is actually there. Drift
arrives from three directions, and all three are yours to handle:

- **Work done in this chat.** If you changed how something behaves, update the documentation in the same
  session, in its own commit (rule 3).
- **Work committed by a human.** Someone else's commit, or a pull from `origin/main`, can silently
  invalidate a note. A `SessionStart` hook runs `check_docs_sync.py` and puts the result in your context:
  if it reports structural changes, review those commits before starting anything else.
- **The Open Knowledge Format specification.** See rule 8.

`check_docs_sync.py` also runs as a `PreToolUse` hook on every `git commit` and **blocks the commit**
when the documentation is internally inconsistent - on any machine, for any developer, because
`.claude/settings.json` is committed. It fails on:

- a top-level heading that appears twice in one document - the signature of appending instead of
  replacing;
- a backticked repository path that does not exist, including anything under a deleted top level listed
  in `REMOVED_ROOTS` (gitignored paths are skipped);
- a markdown link to a file that is not there;
- a note in `docs/ai/notes/` or a record in `docs/ai/decisions/` missing from its index;
- `docs/ai/status.md` over its 60-line budget.

Structural drift - someone changed a documented path - is **reported, never fatal**: rule 3 puts the
documentation in its own commit after the code one, so blocking on it would deadlock that sequence. Run
the script whenever you are unsure. Never report the docs as in sync without having run it.

**Replace, never append.** When a finding changes, rewrite the paragraph that stated the old version -
two paragraphs disagreeing with each other is how a knowledge base becomes untrustworthy. When you delete
a top-level directory, add it to `REMOVED_ROOTS` in the same commit: that is what makes every document
still pointing at it fail the check.

**`status.md` is a handover, and only for what is recorded nowhere else.** History is git and `log.md`;
durable knowledge moves to [notes](docs/ai/notes/index.md) or [decisions](docs/ai/decisions/index.md) as
soon as it is true beyond today; a bug found along the way goes into
[known defects](docs/ai/notes/known-defects.md), not here - say so to the user. What is left is the state of the working branch, and **empty is the normal
state**.

The check cannot read meaning: it catches structure, not a sentence that is simply wrong. So still update
the documentation in the same session as the change (rule 3), and never update it silently: say what you
changed and why.

**7. Close every reply with a wrap-up, then the user's TODO.** The last two sections of any answer
that ends a piece of work, always in this order:

- **In short** - a few plain sentences someone can understand without reading the rest. What happened and
  what it means, in ordinary language: no file paths, no command names, no jargon.
- **Your TODO** - what is now on the user's side: decisions to make, steps to run, approvals waiting.
  Number them if there is more than one. Write "Nothing" when there is genuinely nothing.

Keep both short. They are a summary for someone who skipped the details, not a second copy of them.

**8. The knowledge base is an OKF bundle - keep it conformant.** `docs/ai/` follows the
[Open Knowledge Format](https://github.com/GoogleCloudPlatform/open-knowledge-format) v0.2: plain
markdown, YAML frontmatter, at least a non-empty `type` on every document. When you add or change
something there:

- give it a `type` from the vocabulary in `docs/ai/conventions.md`, plus `title`, `description`, `tags`;
- record provenance with `generated: { by, at }`, and add `verified` only for what you actually checked.
  Actors are `claude-code/<model>` for agents and `human:<id>` for people, so a human-reviewed document is
  distinguishable from a machine-written one;
- `index.md` and `log.md` are reserved for navigation and history - never concept documents;
- add a dated `log.md` entry **only when the bundle's structure or conventions change** - not for each
  finding, which is what the note itself is for, and never for work done, which is git history.

Validate with `python .claude/scripts/check_okf.py` before committing.

The specification is versioned and still evolving. `docs/ai/okf-conformance.md` records the version this
bundle targets, what v0.2 asks of us, and when the spec was last read; `check_docs_sync.py` reports that
date and flags a review once it is over 90 days old. When a newer version exists, **do not migrate
silently**: read the new `SPEC.md`, work out what genuinely affects this bundle, and propose the changes
and any reorganisation to the user before touching anything.

**9. Nothing checks your work but you - run the checks before calling it done.** There is no CI. For any
change under `app/` or `core/`, run the unit tests of the modules you touched and `./gradlew lintDebug`
(`abortOnError = true`) before reporting the work as finished, and say which ones you ran. A UI string
added to `values/strings.xml` must be added to `values-de/` and `values-it/` in the same change -
`TranslationsTest` fails on a missing *or* a stale key. Ladin (`values-b+lld/`) is deliberately partial and
falls back to German. Details and the opt-in network tests:
[test suite](docs/ai/notes/test-suite-and-opt-in-live-tests.md).

**10. Signing material and `local.properties` are off limits.** Every release must be signed with the
same key - a lost or leaked keystore strands every installed copy. Never read, print, copy or commit a
keystore, its passwords, `local.properties` or the `TRANSIT_RELEASE_*` variables. If a build needs them and
they are missing, stop and tell the user. `.gitignore` already excludes `*.jks`, `*.keystore` and
`local.properties`; never weaken that.

**11. Never hard-wrap prose in a README.** One line per paragraph, one line per list item, however long it
runs - a newline means a new paragraph and nothing else. Reflowing a sentence rewrites every line after it,
turning a one-word change into a ten-line diff. Tables, fenced code and headings keep their own lines.
Older wrapped paragraphs in `README.md` are unwrapped when they are next edited, not in a sweep. The
AI-facing material - `CLAUDE.md`, `docs/ai/` and `.claude/` - stays wrapped.

**12. GitHub: read freely, change only after asking - and record bugs in the docs, not as issues.** The
repository `ta-samuelece/SouthTyrolTransit` is read with the GitHub CLI (`gh`), logged in per machine with
`gh auth login`. You may list, search and read issues, pull requests and releases without asking - read an
issue before working on it. Defects you find go into [known defects](docs/ai/notes/known-defects.md) and
are **not** opened as GitHub issues on your own initiative - never on a contributor's fork (forks are
temporary). **At session start**, the `SessionStart` hook lists the open defects; when it does, ask the
user in one `AskUserQuestion`, before their first task, whether to keep tracking them in the file, move
all of them to GitHub issues, or move selected ones. If they choose issues: draft one issue per defect
from its entry, show the exact titles and texts, and only after approval create them on the **main
repository** (`ta-samuelece/SouthTyrolTransit`); then delete the moved entries from the file in an
AI-material commit, since the issue is now the record. "Keep in the file" changes nothing. Any change on GitHub - creating, editing, labelling,
commenting on, closing or reopening an issue, or opening or merging a pull request - needs the user's
explicit approval each time, with the exact title and text shown first; it is public. Logging in is not a change: when `gh` reports it is not logged in, or the login has expired, you
may start `gh auth login --web --hostname github.com --git-protocol https` yourself (in the background -
it prints a one-time code and waits for the user to confirm it in the browser), give the user the code,
and carry on once it completes, instead of stopping the task. On Windows, `gh` may not be on `PATH` in a
shell started before it was installed; its default location is `C:\Program Files\GitHub CLI\gh.exe`.
Contributors without write access push their branch to a personal fork (remote `fork`) and open the pull
request from there into `main`.

## What this repository is

South Tyrol Transit is an unofficial, public-domain Android app for public transport in South Tyrol /
Alto Adige, built only on open data (STA GTFS and GTFS-Realtime via Open Data Hub, the public STA EFA
journey planner, ODH mobility, OpenStreetMap tiles). It is written almost entirely by AI assistants with a
human steering and testing on devices. Published on GitHub as `ta-samuelece/SouthTyrolTransit`; APKs are
distributed as GitHub release assets and installed through the app's own updater.

`README.md` is the authoritative human documentation - features, architecture, modules, data sources,
update strategies, known API limitations, the release process. Read it rather than expecting it restated
here. `docs/API_DISCOVERY.md` details every endpoint; `docs/PUSH_BACKEND.md` is the contract for a push
server that does not exist yet.

## Non-obvious things that will bite you

**A stop board has two sources of live times, not one.** GTFS-RT predictions come first; where GTFS-RT
has nothing (often), the EFA departure monitor's delays are overlaid by matching line and scheduled
minute. The README's realtime section describes only GTFS-RT. Details:
[live times on boards](docs/ai/notes/live-times-two-sources.md).

**Material 3 Expressive is pinned to an alpha** (`material3` 1.5.0-alpha, Compose 1.13.0-alpha). Do not
"upgrade" to the stable line - it lacks the Expressive APIs the design system uses. Opt-ins are per file
(`@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)`). See README section 19.

**MapLibre must stay on the OpenGL ES build** (`org.maplibre.gl:android-sdk-opengl`). The default Vulkan
build crashed the emulator's software GPU and is riskier on old devices at minSdk 26. MapLibre code lives
only in `core/map/src/main/kotlin/org/southtyrol/transit/map/MapLibreTransitMap.kt`.

**`versionCode` goes up by one for every published build, preview or stable.** The updater refuses an
APK whose `versionCode` is not higher than the installed one. Previews rank below their finished version
(`0.2.0-preview.2` < `0.2.0`).

**All clock times are Europe/Rome, regardless of device zone,** and GTFS times run past 24:00. Never use
the system default zone for transit times. Turn a GTFS time into an `Instant` only with
`GtfsTime.instant`, and always pass `serviceDate` when building a GTFS `Departure` - its default is wrong
after midnight. Details: [GTFS time](docs/ai/notes/gtfs-time-and-service-days.md).

**Database and importer changes need extra steps, or they break users' data.** Neither Room database has
migrations: a `UserDatabase` change without one loses saved items. An importer change does not reach
existing installs until STA publishes a feed with a new hash. Details:
[schedule database](docs/ai/notes/schedule-database-lifecycle.md).

**Saved stops and lines are keyed by derived keys** (station key `it:22021:468`, line key `BUS:201`), and
EFA uses different stop ids from GTFS. Changing `GtfsFiles.stationKey`, `GtfsFiles.lineKey` or
`TransportMode` names silently orphans saved items. Details:
[identifiers](docs/ai/notes/identifiers-across-sources.md).

## Layout

- `app/` - Application, Hilt DI, navigation, feature screens as packages under `feature/`, widget,
  workers, the in-app updater (`update/`). Version and signing in `app/build.gradle.kts`.
- `core/model/` - pure Kotlin/JVM: domain models, data-source interfaces (`Interfaces.kt`), realtime merge
  and freshness rules (`Realtime.kt`), time and text utilities. No Android dependency.
- `core/data/` - network, GTFS importer and Room schedule store, EFA client and parsers, GTFS-RT,
  repositories (`TransitRepository.kt`), DataStore settings. Room schemas in `core/data/schemas/`.
- `core/designsystem/`, `core/map/` - theme and components; the provider-agnostic `TransitMap` API.
- `tools/release.py` publishes a release (plain Python 3); `tools/generate_icons.py` regenerates launcher
  icons from `icons/` (needs Pillow and numpy, and is only one of five steps - see
  [app icons](docs/ai/notes/app-icons-and-package-name.md)).
- `docs/` - human documentation, screenshots, and `docs/fixtures/` (captured real API payloads the tests
  use).
- `docs/ai/` - the AI knowledge base, an OKF v0.2 bundle (see "AI workspace" below).

## Workflow

Requires JDK 21+ (Android Studio's bundled JBR works) and the Android SDK with platform android-37.1;
`local.properties` points at the SDK. Then:

```bash
./gradlew assembleDebug
./gradlew :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew lintDebug
```

On Windows use `gradlew.bat` (or `./gradlew` from Git Bash). Dependencies are declared in the version
catalog `gradle/libs.versions.toml`, never inline in a module's `build.gradle.kts`. Releases: the
`publish-release` skill.

## AI workspace

Shared and committed, so every clone and every teammate gets it:

- `docs/ai/` - the knowledge base, an OKF v0.2 bundle. Start at `docs/ai/index.md`; findings and standing
  facts live one per file under `docs/ai/notes/`, decisions under `docs/ai/decisions/`, current handover
  in `docs/ai/status.md`, bundle history in `docs/ai/log.md`.
- `.claude/skills/` runbooks loaded on demand, `.claude/scripts/` reusable helpers, `.claude/settings.json`
  shared permissions and hooks.

Personal and gitignored: `.claude/local/` (scratch), `.claude/settings.local.json`. Personal cross-session
memory lives outside the repo and is deliberately not shared.

When a session produces something durable, promote it into `docs/ai/` or a skill before the session ends.
