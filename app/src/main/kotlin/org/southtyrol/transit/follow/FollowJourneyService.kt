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
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.JourneyFollow
import org.southtyrol.transit.work.Notifications
import java.time.Instant
import javax.inject.Inject

/**
 * Keeps a followed journey running while the app is in the background: runs [FollowEngine] for the
 * [ActiveJourney] session and keeps its notification current. Type `location` while location is in
 * use, `specialUse` otherwise. Not sticky: if Android ends the app, the journey is gone.
 */
@AndroidEntryPoint
class FollowJourneyService : Service() {
    @Inject lateinit var active: ActiveJourney
    @Inject lateinit var engine: FollowEngine
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
            // Swiped away while the journey runs: put it back.
            val session = active.session.value
            if (running && session != null && Notifications.canPost(this)) post(FollowNotifications.build(this, session, active.view.value))
            else if (!running) stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService must be answered with startForeground, even when the journey was
        // stopped in the meantime, or Android ends the app.
        val session = active.session.value
        if (!enterForeground(session)) {
            active.stop()
            shutDown()
            return START_NOT_STICKY
        }
        if (session == null) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            scope.launch {
                active.session.collectLatest { current ->
                    when {
                        current == null -> shutDown()
                        enterForeground(current) -> engine.run(current)
                        else -> active.stop()
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
            val session = active.session.value
            val view = active.view.value
            val now = Instant.now()
            val current = view?.let {
                Shown(
                    session, FollowText.title(resources, it), FollowText.text(resources, it, now), FollowText.chip(resources, it.step.at, now),
                    it.freshness, JourneyFollow.elapsedSeconds(it.journey, now) * 100 / JourneyFollow.totalSeconds(it.journey),
                )
            }
            if (session != null && current != shown && Notifications.canPost(this)) {
                post(FollowNotifications.build(this, session, view, now))
                shown = current
            }
            delay(NOTIFY_MS)
        }
    }

    /** What the notification last showed, to post only changes. */
    private data class Shown(val session: FollowSession?, val title: String, val text: String, val chip: String, val freshness: Freshness, val percent: Long)

    /**
     * Enters the foreground for [session] (a placeholder without one). False when Android refused both the
     * wanted type and the fallback.
     */
    private fun enterForeground(session: FollowSession?): Boolean {
        val notification = FollowNotifications.build(this, session, active.view.value)
        val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val withLocation = session?.useLocation == true && location.hasPermission() && Build.VERSION.SDK_INT >= 29
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
            // Notification permission revoked meanwhile: the journey still runs in the app.
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
