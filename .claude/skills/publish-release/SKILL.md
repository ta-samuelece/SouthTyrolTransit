---
name: publish-release
description: Publish a new version of the app - bump versionCode/versionName, build the signed release APK, write release notes and publish a stable or preview GitHub release with tools/release.py. Use when the user asks to release, ship, publish, cut a version, make a preview/beta, or bump the version. Gated - a published release reaches users' phones through the in-app updater.
---

# Publishing a release

A GitHub release **is** the deploy: the in-app updater offers it to every phone on the matching channel
on its next start. A stable release reaches every user; a preview reaches everyone who opted into the
Preview channel. CI checks the unit tests and lint of what is merged, but nothing builds, tests or
stages the release itself, so this procedure is the only gate. The human-facing
version of it is the "In-app updates and publishing a release" section of `README.md`; this is the agent
procedure on top.

## 1. Agree the version

Read `versionCode` and `versionName` from `app/build.gradle.kts`, then ask the user which release this is
if they have not said. The rules:

| | `versionName` | `versionCode` | Tag | GitHub |
| --- | --- | --- | --- | --- |
| Preview | `0.2.0-preview.1`, then `-preview.2`... | **+1** | `v0.2.0-preview.1` | pre-release, never "latest" |
| Stable | `0.2.0` | **+1** | `v0.2.0` | release, marked "latest" |

- `versionCode` goes up by exactly one for **every** published build. The updater refuses an APK whose
  `versionCode` is not higher than the installed one, so a skipped bump strands users.
- A preview ranks below its finished version (`0.2.0-preview.2` < `0.2.0`), so promoting a preview means
  publishing `0.2.0` as stable with a new `versionCode`.

## 2. Bump, write the notes, check, merge

1. On a branch, edit only `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Write the release notes to `docs/release-notes/<versionName>.md` - committed, so they are reviewed in
   the pull request and the Release workflow finds them. The app renders headings, bullets, **bold** and
   links in its "What's new" dialog. Write for users, not developers: what they will notice. Base them on
   `git log <previous tag>..HEAD`. The script prepends a "Preview build" banner to previews by itself -
   do not add one.
3. Run the unit tests and `./gradlew lintDebug` (CLAUDE.md rule 9). Do not release red.
4. Commit through the `commit-changes` skill (bump and notes together). A version-only commit is titled
   `Release v0.2.0 (versionCode 7)`; when the bump rides on a feature commit, the version ends the title.
5. Get it into `main` through a pull request - `main` rejects direct pushes, CI must be green, and the
   merge needs its own approval (CLAUDE.md rule 2).

## 3. Publish - from GitHub (the usual way)

`.github/workflows/release.yml` builds the signed APK and runs `tools/release.py` on GitHub
([branch protection](../../../docs/ai/notes/github-main-and-tag-protection.md) has the setup). Open a
**dedicated** `AskUserQuestion` - never bundled with a commit or merge question - showing the channel,
tag, `versionCode`, the `main` commit and the notes. Only after an approving answer, start it:

```bash
gh workflow run release.yml --ref main -f channel=preview      # or channel=stable
```

The run then waits in the `release` environment until the **user** approves the deployment on GitHub;
never approve it yourself. It fails in its first step if the channel, `versionName` and notes file
disagree, and in the build if the environment has no signing secrets - setting those is the user's job
alone (CLAUDE.md rule 10). Follow the run with `gh run watch`.

## 4. Publish - from the user's machine (fallback)

Only when the user asks for it. Switch to `main` and pull, then:

```bash
./gradlew assembleRelease
python tools/release.py --preview --notes docs/release-notes/<version>.md --dry-run
python tools/release.py --notes docs/release-notes/<version>.md --dry-run      # stable
```

Signing comes from `local.properties` or the `TRANSIT_RELEASE_*` environment variables, which **you never
read, print or edit** (CLAUDE.md rule 10). If the build produces an unsigned APK or fails on signing, stop
and tell the user. The dry run checks that `versionName` matches the channel, that the APK is signed and
carries exactly this version (needs `JAVA_HOME`, e.g. Android Studio's `jbr`, for `apksigner`), that the
working tree is clean and its commit is on `origin/main`, and that the tag is new. Then ask as in step 3
and only after approval run the same command without `--dry-run`. Do not create or push the tag yourself;
only a repository admin may create `v*` tags, so it fails with a 422 under anyone else's credential.

## 5. After publishing

Report the release URL the script prints (in the workflow's log when published from GitHub). Remind the user that phones on that channel get the update on
their next app start, and that a mistake is fixed by publishing a **newer** version - deleting the GitHub
release does not uninstall anything, and a re-used `versionCode` is refused by every phone that already
has it.
