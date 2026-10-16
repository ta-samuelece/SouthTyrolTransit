---
type: Finding
title: Backup and restore - Android's automatic backup of saved items, settings and the app icon
description: Android Auto Backup carries user.db and the settings file, cloud only and only end-to-end encrypted (Android 9+); a restore marker from TransitBackupAgent triggers RestoreCleanup and re-applies the app icon on the next start; the user database and settings keys are now part of a restore contract; how to test with bmgr (never after a force-stop).
tags: [backup, restore, settings, room, datastore, privacy]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-10-16T15:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-10-16T15:30:00Z"
status: stable
---

Designed with the user on 2026-10-11: automatic backup only - no export file, no in-app switch, no
device-to-device transfer, and nothing unless the backup is end-to-end encrypted.

# What is backed up, and where that is decided

| Android | Rule file | Effect |
| --- | --- | --- |
| 12+ | `res/xml/data_extraction_rules.xml` | `cloud-backup disableIfNoEncryptionCapabilities="true"`; `device-transfer` excludes everything |
| 9-11 | `res/xml-v28/backup_rules.xml` | the same files, each with `requireFlags="clientSideEncryption"` |
| 8 | `res/xml/backup_rules.xml`, `values/bools.xml` | `allowBackup` is `false` (`@bool/backup_allowed`, true in `values-v28`) and the rules exclude everything: Android 8 cannot require encryption |

The included files are `databases/user.db` (+ `-wal`) and `files/datastore/settings.preferences_pb`.
Whole files are restored, so the rules cannot pick tables or keys. Everything else - the timetable,
WorkManager's database, Glance widget state, AppCompat's locale file - is outside the rules.

# What happens on restore

`TransitBackupAgent` (`fullBackupOnly`, so the file rules still apply) only writes the marker
`noBackupFilesDir/restored` in `onRestoreFinished`: it runs before Hilt's Application exists. On the next
start `TransitApplication.syncBackupState` sees the marker, runs `RestoreCleanup.apply` (clears recents,
caches and the notified-alert list; switches alert notifications off, because the notification
permission is not restored; clears the skipped update), re-applies the launcher icon from
`UserSettings.appIcon`, and deletes the marker. Without a marker it mirrors the current icon into
`appIcon`, since activity-alias state is never backed up. The picker also writes it on every change.

Recents briefly exist between the restore and that first start; nothing reads them before it in practice.

`onboardingDone` comes back with the settings, so a restored phone skips the first-run screens. The
notification permission does not come back, and `RestoreCleanup` switches alert notifications off, so on a
restored phone they have to be turned on again in Settings.

# Consequences for future changes

- **`user.db` and the settings keys are a restore contract.** A backup from an older version is restored
  into a newer one (Android skips restores into older versions; `restoreAnyVersion` is not set). A
  `UserDatabase` version bump without a migration loses restored saved items too, and renaming a
  DataStore key silently drops that setting from every backup.
- Anything new in either file that must not come back belongs in `RestoreCleanup.apply` (pinned by
  `BackupTest`).
- Settings says nothing about the backup: its info line and "Open backup settings" shortcut were
  removed (2026-10-16) at the user's request.
- The privacy and "saved items" texts say items are in the phone's encrypted backup; keep them true.

# Testing with bmgr

On a Google-APIs emulator: `bmgr enable true`, `bmgr transport com.android.localtransport/.LocalTransport`,
`settings put secure backup_local_transport_parameters "fake_encryption_flag=true,is_encrypted=true"`,
switch transports away and back so it rereads them, and set a lock with `locksettings set-pin 1234`.
**Launch the app normally before `bmgr backupnow`:** a force-stopped package is refused with "Backup is
not allowed", which looks like a rules problem but is not. Then uninstall and `adb install` restores at
install time. Put the emulator back afterwards (Google transport, `bmgr enable false`, clear the PIN).

# What was checked

On 2026-10-11, on an Android 16 emulator: with encryption and a PIN, a backup carried a saved stop, dark
plus pure black, the Night Route icon and notifications on; after uninstall and reinstall only `user.db`,
its WAL and the settings file came back, the stop and theme were there, the icon was re-applied,
notifications were off, recents, cache and notified list were empty, and the marker was gone. Without
encryption the transport received nothing for the app. The shortcut opened Google's backup screen; its
fallback and Android 8-11 were not run (reasoned from the platform documentation).
