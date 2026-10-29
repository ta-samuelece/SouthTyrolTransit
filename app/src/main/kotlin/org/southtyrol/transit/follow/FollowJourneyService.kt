package org.southtyrol.transit.follow

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.model.JourneyFollow
import org.southtyrol.transit.model.RideFollow
import org.southtyrol.transit.work.Notifications
import java.time.Instant
import javax.inject.Inject

/**
 * Keeps a followed journey or ride running while the app is in the background: runs [FollowEngine] or
 * [RideEngine] for what [ActiveJourney] follows and keeps its notification current. Type `location`
 * while a journey uses location, `specialUse` otherwise. Not sticky: if Android ends the app, what was
 * followed is gone.
 */
@AndroidEntryPoint
class FollowJourneyService : Service() {
    @Inject lateinit var active: ActiveJourney
    @Inject lateinit var engine: FollowEngine
    @Inject lateinit var rideEngine: RideEngine
    @Inject lateinit var location: LocationProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The Stop action is a plain start, not a foreground one: nothing to answer.
            active.stop()
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISMISSED) {
            // Swiped away while following: put it back.
            val followed = active.followed.value
            if (running && followed != null && Notifications.canPost(this)) post(notification(followed, Instant.now()))
            else if (!running) stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService must be answered with startForeground, even when following was
        // stopped in the meantime, or Android ends the app.
        val followed = active.followed.value
        if (!enterForeground(followed)) {
            active.stop()
            shutDown()
            return START_NOT_STICKY
        }
        if (followed == null) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            scope.launch {
                active.followed.collectLatest { current ->
                    when {
                        current == null -> shutDown()
                        !enterForeground(current) -> active.stop()
                        current is FollowSession -> engine.run(current)
                        current is RideSession -> rideEngine.run(current)
                    }
                }
            }
            scope.launch { keepNotificationCurrent() }
        }
        return START_NOT_STICKY
    }

    /** The text counts minutes down: checks every few seconds and posts only what changed. */
    private suspend fun keepNotificationCurrent() {
        var shown: Shown? = null
        while (true) {
            val followed = active.followed.value
            val now = Instant.now()
            val current = when (followed) {
                is FollowSession -> active.view.value?.let {
                    Shown(
                        followed, FollowText.title(resources, it), FollowText.text(resources, it, now), FollowText.chip(resources, it.step.at, now),
                        FollowText.source(resources, it), JourneyFollow.elapsedSeconds(it.journey, now) * 100 / JourneyFollow.totalSeconds(it.journey),
                    )
                }
                is RideSession -> active.rideView.value?.let {
                    Shown(
                        followed, FollowText.rideTitle(resources, followed, it), FollowText.rideText(resources, followed, it, now), FollowText.chip(resources, it.step.at, now),
                        FollowText.rideSource(resources, it), RideFollow.elapsedSeconds(it.detail, now) * 100 / RideFollow.totalSeconds(it.detail),
                    )
                }
                null -> null
            }
            if (followed != null && current != shown && Notifications.canPost(this)) {
                post(notification(followed, now))
                shown = current
            }
            delay(NOTIFY_MS)
        }
    }

    /** What the notification last showed, to post only changes. */
    private data class Shown(val followed: Followed, val title: String, val text: String, val chip: String, val source: String, val percent: Long)

    /** The notification of [followed] as it stands; the placeholder without anything followed. */
    private fun notification(followed: Followed?, now: Instant): Notification = when (followed) {
        is RideSession -> FollowNotifications.ride(this, followed, active.rideView.value, now)
        is FollowSession -> FollowNotifications.build(this, followed, active.view.value, now)
        null -> FollowNotifications.build(this, null, null, now)
    }

    /**
     * Enters the foreground for [followed] (a placeholder without one). False when Android refused both the
     * wanted type and the fallback.
     */
    private fun enterForeground(followed: Followed?): Boolean {
        val notification = notification(followed, Instant.now())
        val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val withLocation = (followed as? FollowSession)?.useLocation == true && location.hasPermission() && Build.VERSION.SDK_INT >= 29
        val type = if (withLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else fallback
        return start(notification, type) || (type != fallback && start(notification, fallback))
    }

    private fun start(notification: Notification, type: Int): Boolean = try {
        ServiceCompat.startForeground(this, Notifications.ID_FOLLOW, notification, type)
        true
    } catch (e: Exception) {
        // SecurityException (type not allowed) or ForegroundServiceStartNotAllowedException.
        false
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun post(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_FOLLOW, notification)
        } catch (_: SecurityException) {
            // Notification permission revoked meanwhile: following still runs in the app.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.southtyrol.transit.follow.STOP"
        const val ACTION_DISMISSED = "org.southtyrol.transit.follow.DISMISSED"
        private const val NOTIFY_MS = 5_000L
    }
}
