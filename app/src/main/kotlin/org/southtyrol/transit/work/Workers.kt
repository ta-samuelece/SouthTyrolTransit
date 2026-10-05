package org.southtyrol.transit.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.southtyrol.transit.MainActivity
import org.southtyrol.transit.R
import org.southtyrol.transit.data.AlertMatcher
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LineRepository
import org.southtyrol.transit.data.NotifiedAlertRow
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.data.ScheduleStore
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.UserDao
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.localized
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object Notifications {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_SYNC = "sync"
    const val ID_SYNC = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_alerts_description)
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW))
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Schedules the honest, battery-friendly background work: timetable refresh and opt-in alert checks. */
@Singleton
class BackgroundWork @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Weekly timetable refresh; unmetered network by default because the feed is ~150 MB. */
    fun scheduleTimetableRefresh(allowMetered: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .setRequiresBatteryNotLow(true)
            .build()
        workManager.enqueueUniquePeriodicWork(
            ScheduleSyncWorker.PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ScheduleSyncWorker>(7, TimeUnit.DAYS).setConstraints(constraints).build(),
        )
        // Also try once soon after install so the offline timetable becomes available.
        workManager.enqueueUniqueWork(
            ScheduleSyncWorker.INITIAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScheduleSyncWorker>().setConstraints(constraints).build(),
        )
    }

    /** User-initiated download (e.g. "Download now"): any network, runs as soon as possible. */
    fun downloadTimetableNow() {
        workManager.enqueueUniqueWork(
            ScheduleSyncWorker.MANUAL, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(ScheduleSyncWorker.KEY_MANUAL to true))
                .build(),
        )
    }

    fun setAlertChecks(enabled: Boolean) {
        if (!enabled) { workManager.cancelUniqueWork(AlertCheckWorker.NAME); return }
        workManager.enqueueUniquePeriodicWork(
            AlertCheckWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AlertCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    fun timetableWork() = workManager.getWorkInfosForUniqueWorkFlow(ScheduleSyncWorker.MANUAL)
}

@HiltWorker
class ScheduleSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: ScheduleStore,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(applicationContext.getString(R.string.sync_downloading), 0, 0)

    private fun foreground(text: String, progress: Int, max: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_transit)
            .setContentTitle(applicationContext.getString(R.string.sync_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_SYNC, notification)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = settings.current()
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            store.refresh(allowFtp = prefs.ftpFallback)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.error.toString()))
        }
    }

    companion object {
        const val PERIODIC = "timetable-periodic"
        const val INITIAL = "timetable-initial"
        const val MANUAL = "timetable-manual"
        const val KEY_MANUAL = "manual"
    }
}

/**
 * Periodic (≥15 min, best-effort) check for new disruptions on saved stops and lines. This is NOT
 * realtime push: Android may defer it for battery reasons. See docs/PUSH_BACKEND.md.
 */
@HiltWorker
class AlertCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alerts: AlertRepository,
    private val saved: SavedRepository,
    private val lines: LineRepository,
    private val user: UserDao,
    private val settings: SettingsRepository,
    private val language: LanguageProvider,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.current().alertNotifications || !Notifications.canPost(applicationContext)) return Result.success()
        val lang = language.current()
        alerts.refresh(lang, force = true)
        val state = alerts.state.first()
        val items = saved.saved.first()
        val stops = items.filter { it.kind == SavedKind.STOP }.map { it.id }.toSet()
        val savedLines = items.filter { it.kind == SavedKind.LINE }.mapNotNull { runCatching { lines.line(it.id) }.getOrNull() }
        if (stops.isEmpty() && savedLines.isEmpty()) return Result.success()
        val now = Instant.now()
        val already = user.notifiedAlerts().toSet()
        val relevant = state.alerts.filter { a ->
            a.active(now) && a.id !in already &&
                (stops.any { AlertMatcher.affectsStop(a, it) } || savedLines.any { AlertMatcher.affectsLine(a, it) })
        }
        val manager = NotificationManagerCompat.from(applicationContext)
        for (alert in relevant.take(5)) {
            val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_transit)
                .setContentTitle(alert.headers.localized(lang).ifBlank { applicationContext.getString(R.string.alerts_title) })
                .setContentText(alert.descriptions.localized(lang))
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.descriptions.localized(lang)))
                .setContentIntent(Notifications.openAppIntent(applicationContext))
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(alert.id.hashCode(), notification)
            } catch (_: SecurityException) {
                return Result.success()
            }
            user.markNotified(NotifiedAlertRow(alert.id, now.toEpochMilli()))
        }
        user.pruneNotified(now.minusSeconds(30L * 86_400).toEpochMilli())
        return Result.success()
    }

    companion object {
        const val NAME = "alert-checks"
    }
}
