package org.southtyrol.transit

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.southtyrol.transit.work.BackgroundWork
import org.southtyrol.transit.work.Notifications
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class TransitApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backgroundWork: BackgroundWork
    @Inject lateinit var settings: org.southtyrol.transit.data.SettingsRepository
    @Inject lateinit var scheduleStore: org.southtyrol.transit.data.ScheduleStore
    @Inject lateinit var userDao: org.southtyrol.transit.data.UserDao

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        org.southtyrol.transit.di.AppLanguage.dropLadin()
        // Before anything else, so an update never leaves two launcher entries for longer than needed.
        org.southtyrol.transit.ui.AppIcons.reconcile(this)
        // Registering the periodic sync replaces its constraints, so it must use the saved mobile-data
        // choice; reading settings is a suspend call, hence off the main thread. The one-off initial sync
        // is only for installs that have no timetable yet.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            syncBackupState()
            backgroundWork.scheduleTimetableRefresh(
                allowMetered = settings.current().scheduleOnMetered,
                initialSync = scheduleStore.activeName() == null,
            )
        }
    }

    /**
     * After a restore: drop what the backup must not bring back and re-apply the launcher icon, which
     * Android does not restore. Otherwise record the current icon in the settings so the next backup
     * carries it.
     */
    private suspend fun syncBackupState() {
        val marker = org.southtyrol.transit.data.RestoreCleanup.marker(noBackupFilesDir)
        if (marker.exists()) {
            org.southtyrol.transit.data.RestoreCleanup.apply(settings, userDao)
            val icon = org.southtyrol.transit.ui.AppIcon.entries.firstOrNull { it.name == settings.current().appIcon }
            if (icon != null && icon != org.southtyrol.transit.ui.AppIcons.current(this)) org.southtyrol.transit.ui.AppIcons.set(this, icon)
            marker.delete()
        } else {
            val icon = org.southtyrol.transit.ui.AppIcons.current(this).name
            if (settings.current().appIcon != icon) settings.setAppIcon(icon)
        }
    }
}
