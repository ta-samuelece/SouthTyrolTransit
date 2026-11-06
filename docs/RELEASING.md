# Updates and releases

The app updates itself from **GitHub Releases** of [ta-samuelece/SouthTyrolTransit](https://github.com/ta-samuelece/SouthTyrolTransit). There are two **update channels**, chosen in Settings → App updates:

| Channel | Offered | GitHub |
|---|---|---|
| **Stable** (default) | finished releases only | normal releases, tag `vX.Y.Z` (asks `releases/latest`, which never returns a pre-release) |
| **Preview** | the newest of previews *and* stable releases | pre-releases, tag `vX.Y.Z-preview.N`, plus normal releases (asks the release list) |

- The check runs on start (switchable) and on "Check for updates"; switching channel checks right away.
- If the release's version is newer than the installed `versionName`, a bottom sheet offers it: the version, highlight cards in the app's language and a way to all changes, with **Update**, **Later** or **Skip this version** always in reach. Previews carry a "Preview" label. A release without cards, or if its `whats-new.json` cannot be fetched, shows its plain notes instead. The sheet cannot be swiped away during a download. Settings' "Update to" button reopens it, and "What's new in this version" there shows the installed version's own notes, offline.
- Versions compare numerically, and a preview ranks below the finished version: `0.2.0-preview.1` < `0.2.0-preview.2` < `0.2.0`. So preview testers are moved onto `0.2.0` when it ships, and stable users never see a preview.
- Going back from Preview to Stable never downgrades: the preview stays installed until a newer stable version exists.
- The APK asset (a name containing `release` or `preview` is preferred; `debug` builds are never preferred) is downloaded with progress. Before installing, the app checks that the APK is **this app**, has a **higher `versionCode`**, and is signed with the **same certificate** as the installed app.
- It then opens Android's package installer. The first time, Android asks to allow "install unknown apps" for this app, and every install needs the user's confirmation. Nothing installs silently.
- The downloaded APK is deleted on the next app start once it is installed (any file in the download folder that is not newer than the running version goes).
- The repository is set by `update.repo` in `local.properties` (or `TRANSIT_UPDATE_REPO`). Set it to `none` to build without updates (e.g. for an app-store build).

## Signing key (once)

Every release, stable or preview, must be signed with the **same key**: Android refuses updates signed with another one, so losing the keystore means users must uninstall and reinstall. Create it once, keep it and its passwords safe and out of git:

```bash
keytool -genkeypair -v -keystore release.jks -alias southtyroltransit -keyalg RSA -keysize 4096 -validity 36500
```
```properties
# local.properties
release.storeFile=/absolute/path/to/release.jks
release.storePassword=...
release.keyAlias=southtyroltransit
release.keyPassword=...
```
(or the environment variables `TRANSIT_RELEASE_STORE_FILE`, `TRANSIT_RELEASE_STORE_PASSWORD`, `TRANSIT_RELEASE_KEY_ALIAS`, `TRANSIT_RELEASE_KEY_PASSWORD`). `assembleRelease` then signs automatically.

## Publishing a release (stable or preview)

1. **Choose the version** in `app/build.gradle.kts`:
   - `versionCode`: **always +1**, for every build you publish, preview or stable. The app refuses an update whose `versionCode` is not higher than the installed one.
   - `versionName`: stable `0.2.0`; preview `0.2.0-preview.1`, then `0.2.0-preview.2`, … and finally `0.2.0` for the finished release.
2. **Write the notes** in `docs/release-notes/<versionName>.md`, e.g. `0.2.0-preview.1.md` (headings, bullets, **bold** and links are shown formatted in the app). A stable release also needs `.de.md`, `.it.md` and `.highlights.json` next to it; see [docs/release-notes/README.md](release-notes/README.md).
3. **Merge** both into `main` through a pull request. `main` is protected: direct pushes are rejected, and only a repository admin (or the Release workflow, after the owner's approval) can create the `v*` tags a release needs. The release is tagged on the merged commit.
4. **Publish from GitHub**: *Actions → Release → Run workflow*, pick `preview` or `stable`, then approve the waiting deployment. The workflow checks that the channel, `versionName` and notes file agree, builds the signed APK with the signing key stored as secrets of the `release` environment, and runs `tools/release.py`, which publishes it.

Or publish from your own machine instead of step 4: check out `main` and pull, build the signed APK with `gradlew assembleRelease` (output `app/build/outputs/apk/release/app-release.apk`), and run the script (needs Python 3 and `JAVA_HOME` set, e.g. to Android Studio's `jbr`):
```bash
python tools/release.py --preview --notes docs/release-notes/<versionName>.md --dry-run   # checks only
python tools/release.py --preview --notes docs/release-notes/<versionName>.md             # publishes the preview
python tools/release.py --notes docs/release-notes/<versionName>.md                       # a stable release
```
The script checks that the version matches the channel, that the APK is signed and has exactly that version, that everything is committed and the commit is on `origin/main`, and that the tag is new. Then it creates the tag and the GitHub release on that commit (previews as **pre-release**, never marked "latest"), adds a "Preview build" note to previews and uploads the APK as `SouthTyrolTransit-<version>.apk` (previews) or `SouthTyrolTransit-<version>-release.apk`. Locally it uses the GitHub login git already has; in the workflow, the workflow's own token.

### Signing secrets for the Release workflow

The workflow signs with the same key as local builds, stored as secrets of the `release` environment (*Settings → Environments → release*): `TRANSIT_RELEASE_STORE_BASE64` (the keystore file, base64-encoded), `TRANSIT_RELEASE_STORE_PASSWORD`, `TRANSIT_RELEASE_KEY_ALIAS` and `TRANSIT_RELEASE_KEY_PASSWORD`. A fifth secret, `TRANSIT_RELEASE_GITHUB_TOKEN`, is the owner's fine-grained personal access token (only this repository, permission *Contents: Read and write*): the release is created with it because only an admin may create `v*` tags, and the workflow's own token is not an admin. When it expires, set a new one. Only the repository owner sets these secrets, by hand. GitHub secrets cannot be read back, so they are not a backup: keep the keystore safe elsewhere too. The `release` environment only runs from `main` and waits for the owner's approval, so a run started by anyone else publishes nothing until approved.

Apps on the matching channel offer the update on their next start. To promote a preview, publish the finished version (`0.2.0`, higher `versionCode`) as a normal release; both channels then get it.
