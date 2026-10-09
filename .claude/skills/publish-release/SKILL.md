---
name: publish-release
description: Publish a new version of the app - bump versionCode/versionName, build the signed release APK, write release notes and publish a stable or preview GitHub release with tools/release.py. Use when the user asks to release, ship, publish, cut a version, make a preview/beta, or bump the version. Gated - a published release reaches users' phones through the in-app updater.
---

# Publishing a release

A GitHub release **is** the deploy: the in-app updater offers it to every phone on the matching channel
on its next start. A stable release reaches every user; a preview reaches everyone who opted into the
Preview channel. There is no CI and no staging, so this procedure is the only gate. The human-facing
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

## 2. Bump, check, commit, push

1. Edit only `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run the unit tests and `./gradlew lintDebug` (CLAUDE.md rule 9). Do not release red.
3. Commit through the `commit-changes` skill. A version-only commit is titled
   `Release v0.2.0 (versionCode 7)`; when the bump rides on a feature commit, the version ends the title.
4. Push. If the branch is `main`, that push needs its own approval (CLAUDE.md rule 2).
   `tools/release.py` refuses to run - even with `--dry-run` - until the commit is on GitHub.

## 3. Build the signed APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Signing comes from `local.properties` or the
`TRANSIT_RELEASE_*` environment variables, which **you never read, print or edit** (CLAUDE.md rule 10).
If the build produces an unsigned APK or fails on signing, stop and tell the user to set up signing
themselves - every release must be signed with the same key, and a wrong key strands every installed copy.

## 4. Write the notes

Write the release notes as Markdown in `.claude/local/release-notes-<version>.md` (gitignored). The app
renders headings, bullets, **bold** and links in its "What's new" dialog. Write for users, not
developers: what they will notice. Base them on `git log <previous tag>..HEAD`. Show the notes to the
user. The script prepends a "Preview build" banner to previews by itself - do not add one.

## 5. Dry run, then ask

```bash
python tools/release.py --preview --notes .claude/local/release-notes-<version>.md --dry-run
python tools/release.py --notes .claude/local/release-notes-<version>.md --dry-run      # stable
```

The dry run checks that `versionName` matches the channel, that the APK is signed and carries exactly
this version (needs `JAVA_HOME`, e.g. Android Studio's `jbr`, for `apksigner`), that the working tree is
clean and pushed, and that the tag is new. Fix anything it reports.

Then open a **dedicated** `AskUserQuestion` - never bundled with a commit or push question - showing:
the channel, tag, `versionCode`, commit hash, asset name and the notes. Only after an approving answer,
run the same command without `--dry-run`. The script creates the tag and the release through the GitHub
API; do not create or push the tag yourself.

## 6. After publishing

Report the release URL the script prints. Remind the user that phones on that channel get the update on
their next app start, and that a mistake is fixed by publishing a **newer** version - deleting the GitHub
release does not uninstall anything, and a re-used `versionCode` is refused by every phone that already
has it.
