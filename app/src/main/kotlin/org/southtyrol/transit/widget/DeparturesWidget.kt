package org.southtyrol.transit.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.southtyrol.transit.MainActivity
import org.southtyrol.transit.R
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.BoardSource
import org.southtyrol.transit.data.DepartureRepository
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.ThemeMode
import org.southtyrol.transit.design.TransitTheme
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TransitZone
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/** One departure as stored in the widget state (the widget renders from storage, never from network). */
@Serializable
data class WidgetDeparture(
    val line: String,
    val destination: String,
    val scheduled: Long,
    val predicted: Long? = null,
    val cancelled: Boolean = false,
    val color: Long? = null,
    val textColor: Long? = null,
) {
    val best: Long get() = predicted ?: scheduled
}

internal object WidgetState {
    val stopKey = stringPreferencesKey("stopKey")
    val stopName = stringPreferencesKey("stopName")
    val departures = stringPreferencesKey("departures")
    val updatedAt = longPreferencesKey("updatedAt")
    val failedAt = longPreferencesKey("failedAt")
    val json = Json { ignoreUnknownKeys = true }

    fun decode(prefs: Preferences): List<WidgetDeparture> =
        prefs[departures]?.let { runCatching { json.decodeFromString<List<WidgetDeparture>>(it) }.getOrNull() }.orEmpty()
}

/** Home-screen widget: the next departures at one chosen stop, refreshed in the background. */
class DeparturesWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent { GlanceTheme { Content(appWidgetId) } }
    }

    @Composable
    private fun Content(appWidgetId: Int) {
        val context = LocalContext.current
        val prefs = currentState<Preferences>()
        val stopKey = prefs[WidgetState.stopKey]
        val stopName = prefs[WidgetState.stopName].orEmpty()
        val now = Instant.now().epochSecond
        // Rows that have left since the last refresh are dropped at render time.
        val rows = WidgetState.decode(prefs).filter { it.best >= now - 60 }
        val updated = prefs[WidgetState.updatedAt]
        val failed = prefs[WidgetState.failedAt]
        val size = LocalSize.current
        val capacity = ((size.height.value - 64) / 36).toInt().coerceAtLeast(1)

        val openStop = if (stopKey != null) actionStartActivity(MainActivity.openStopIntent(context, stopKey, stopName))
        else actionStartActivity(WidgetConfigActivity.intent(context, appWidgetId))

        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(openStop), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        stopName.ifBlank { context.getString(R.string.widget_choose_stop) },
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    val status = when {
                        stopKey == null -> context.getString(R.string.widget_tap_to_choose)
                        failed != null && (updated == null || failed > updated) && updated != null -> context.getString(R.string.widget_update_failed, clock(updated))
                        updated != null -> context.getString(R.string.widget_updated, clock(updated))
                        else -> context.getString(R.string.widget_loading)
                    }
                    Text(status, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
                }
                if (stopKey != null) Image(
                    ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = context.getString(R.string.widget_refresh),
                    modifier = GlanceModifier.size(32.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
                    colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.primary),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            when {
                stopKey == null -> Unit
                rows.isEmpty() && updated != null -> Text(
                    context.getString(R.string.stop_no_departures),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                    modifier = GlanceModifier.clickable(openStop),
                )
                else -> rows.take(capacity).forEach { d ->
                    Row(GlanceModifier.fillMaxWidth().height(36.dp).clickable(openStop), verticalAlignment = Alignment.CenterVertically) {
                        val badge = d.color?.let { Color(it.toInt()) } ?: Color(0xFF00695C)
                        val badgeText = d.textColor?.let { Color(it.toInt()) } ?: Color.White
                        Box(
                            GlanceModifier.width(52.dp).background(ColorProvider(badge)).cornerRadius(8.dp).padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(d.line, style = TextStyle(color = ColorProvider(badgeText), fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        }
                        Spacer(GlanceModifier.width(10.dp))
                        Text(
                            d.destination,
                            style = TextStyle(color = if (d.cancelled) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface, fontSize = 14.sp),
                            maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        TimeLabel(d)
                    }
                }
            }
        }
    }

    @Composable
    private fun TimeLabel(d: WidgetDeparture) {
        val context = LocalContext.current
        Column(horizontalAlignment = Alignment.End) {
            if (d.cancelled) {
                Text(context.getString(org.southtyrol.transit.design.R.string.ds_cancelled), style = TextStyle(color = ColorProvider(Color(0xFFB3261E)), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            } else {
                Text(clock(d.best), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                val delay = d.predicted?.let { (it - d.scheduled) / 60 }
                if (delay != null) Text(
                    if (delay > 0) "+$delay" else if (delay < 0) "$delay" else context.getString(R.string.widget_on_time),
                    style = TextStyle(color = ColorProvider(if (delay > 1) Color(0xFFB45309) else Color(0xFF2E7D32)), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }

    companion object {
        private val clockFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        fun clock(epochSecond: Long): String = clockFormat.format(Instant.ofEpochSecond(epochSecond).atZone(TransitZone))
    }
}

class DeparturesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DeparturesWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdates.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdates.cancel(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = WidgetUpdates.refreshNow(context)
}

object WidgetUpdates {
    private const val PERIODIC = "widget-refresh"
    private const val NOW = "widget-refresh-now"

    /** Every 15 minutes (Android's minimum for background work) while at least one widget exists. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES).build())
        refreshNow(context)
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

/** Loads the next departures for every placed widget (timetable + live times) and re-renders them. */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val departures: DepartureRepository,
    private val realtime: RealtimeRepository,
    private val alerts: AlertRepository,
    private val language: LanguageProvider,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = GlanceAppWidgetManager(applicationContext)
        val ids = manager.getGlanceIds(DeparturesWidget::class.java)
        if (ids.isEmpty()) return Result.success()
        runCatching { departures.pollRealtime() }
        for (id in ids) {
            val prefs = androidx.glance.appwidget.state.getAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id)
            val stopKey = prefs[WidgetState.stopKey] ?: continue
            val now = Instant.now()
            try {
                val lang = language.current()
                val (list, source) = departures.load(stopKey, false, now, lang, Duration.ofHours(3))
                val live = if (source == BoardSource.SCHEDULE) departures.liveOverlay(stopKey, now, false, lang) else null
                val board = departures.merge(list, source, realtime.snapshot.value, alerts.state.first().alerts, false, now, live = live)
                val rows = board.departures.take(12).map {
                    WidgetDeparture(
                        line = it.line, destination = it.destination, scheduled = it.scheduled.epochSecond, predicted = it.predicted?.epochSecond,
                        cancelled = it.state == ServiceState.CANCELLED || it.state == ServiceState.SKIPPED, color = it.color, textColor = it.textColor,
                    )
                }
                updateAppWidgetState(applicationContext, id) { p ->
                    p[WidgetState.departures] = WidgetState.json.encodeToString(rows)
                    p[WidgetState.updatedAt] = now.epochSecond
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateAppWidgetState(applicationContext, id) { p -> p[WidgetState.failedAt] = now.epochSecond }
            }
            DeparturesWidget().update(applicationContext, id)
        }
        return Result.success()
    }
}

/** Chooses the widget's stop: shown when a widget is placed or reconfigured. */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            val prefs = settings.settings.collectAsStateWithLifecycle(org.southtyrol.transit.data.UserSettings()).value
            val dark = when (prefs.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            TransitTheme(darkTheme = dark, dynamicColor = prefs.dynamicColor) {
                CompositionLocalProvider(org.southtyrol.transit.LocalDarkTheme provides dark) {
                    org.southtyrol.transit.feature.common.StopSearchSheet(
                        onPick = { stop -> choose(appWidgetId, stop.stationKey.ifBlank { stop.id }, stop.name) },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    private fun choose(appWidgetId: Int, stopKey: String, name: String) {
        lifecycleScope.launch {
            val id = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, id) { p ->
                p[WidgetState.stopKey] = stopKey
                p[WidgetState.stopName] = name
                p.remove(WidgetState.departures)
                p.remove(WidgetState.updatedAt)
            }
            DeparturesWidget().update(this@WidgetConfigActivity, id)
            WidgetUpdates.schedule(this@WidgetConfigActivity)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    companion object {
        fun intent(context: Context, appWidgetId: Int): Intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
