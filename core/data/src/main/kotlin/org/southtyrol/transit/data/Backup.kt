package org.southtyrol.transit.data

import java.io.File

/**
 * Android's automatic backup carries the user database and the settings file (rules in
 * `res/xml/data_extraction_rules.xml` and `res/xml-v28/backup_rules.xml`). Whole files are restored, so
 * what the backup must not bring back is removed after a restore, before the app uses the data.
 */
object RestoreCleanup {
    /** Written by the backup agent when a restore finishes; lives in the no-backup directory. */
    fun marker(noBackupDir: File) = File(noBackupDir, "restored")

    /**
     * Recents, caches and the notified-alert list are device history, not set-up work. Alert
     * notifications come back off because Android does not restore the notification permission, and a
     * skipped update is offered again.
     */
    suspend fun apply(settings: SettingsRepository, user: UserDao) {
        settings.setAlertNotifications(false)
        settings.setSkippedUpdate("")
        user.clearRecent()
        user.pruneCache(Long.MAX_VALUE)
        user.pruneNotified(Long.MAX_VALUE)
    }
}
