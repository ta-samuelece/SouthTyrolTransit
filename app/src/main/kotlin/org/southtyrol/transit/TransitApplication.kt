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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Registering the periodic sync replaces its constraints, so it must use the saved mobile-data
        // choice; reading settings is a suspend call, hence off the main thread. The one-off initial sync
        // is only for installs that have no timetable yet.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            backgroundWork.scheduleTimetableRefresh(
                allowMetered = settings.current().scheduleOnMetered,
                initialSync = scheduleStore.activeName() == null,
            )
        }
    }
}
