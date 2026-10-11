package org.southtyrol.transit

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import org.southtyrol.transit.data.RestoreCleanup

/**
 * Only marks that a restore happened. The backup itself is Android's file-based automatic backup
 * (`fullBackupOnly`), so the key-value callbacks are unused. The agent runs before the app's own
 * Application exists, so it cannot clean up itself: [TransitApplication] does that on the next start.
 */
class TransitBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        runCatching { RestoreCleanup.marker(noBackupFilesDir).createNewFile() }
    }
}
