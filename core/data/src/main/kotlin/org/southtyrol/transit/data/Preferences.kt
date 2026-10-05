package org.southtyrol.transit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.WalkingSpeed
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Tab shown when the app opens. */
enum class StartTab { PLAN, DEPARTURES, MAP, ALERTS, SETTINGS }

/** Where the map's "center on my position" button sits (or whether it is shown at all). */
enum class LocateButtonPosition { BOTTOM_END, BOTTOM_CENTER, BOTTOM_START, HIDDEN }

data class UserSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val alertNotifications: Boolean = false,
    val mobilityLayers: Set<MobilityKind> = setOf(MobilityKind.PARKING),
    val walkingSpeed: WalkingSpeed = WalkingSpeed.NORMAL,
    val wheelchair: Boolean = false,
    /** The static schedule is ~150 MB; by default it only downloads on unmetered networks. */
    val scheduleOnMetered: Boolean = false,
    /** Allow the unencrypted STA FTP mirror when the HTTPS source is down. */
    val ftpFallback: Boolean = true,
    val showVehicles: Boolean = true,
    val startTab: StartTab = StartTab.PLAN,
    val showStops: Boolean = true,
    val locateButton: LocateButtonPosition = LocateButtonPosition.BOTTOM_END,
)

/** The app language currently in effect (per-app locale or system), as a BCP-47 tag. */
fun interface LanguageProvider {
    fun current(): String
}

private val Context.settingsStore by preferencesDataStore("settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsStore)

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val alerts = booleanPreferencesKey("alertNotifications")
        val layers = stringPreferencesKey("mobilityLayers")
        val walking = stringPreferencesKey("walkingSpeed")
        val wheelchair = booleanPreferencesKey("wheelchair")
        val metered = booleanPreferencesKey("scheduleOnMetered")
        val ftp = booleanPreferencesKey("ftpFallback")
        val vehicles = booleanPreferencesKey("showVehicles")
        val startTab = stringPreferencesKey("startTab")
        val stops = booleanPreferencesKey("showStops")
        val locate = stringPreferencesKey("locateButton")
    }

    val settings: Flow<UserSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            UserSettings(
                theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                dynamicColor = p[Keys.dynamic] ?: true,
                alertNotifications = p[Keys.alerts] ?: false,
                mobilityLayers = p[Keys.layers]?.split(',')?.mapNotNull { runCatching { MobilityKind.valueOf(it) }.getOrNull() }?.toSet() ?: setOf(MobilityKind.PARKING),
                walkingSpeed = p[Keys.walking]?.let { runCatching { WalkingSpeed.valueOf(it) }.getOrNull() } ?: WalkingSpeed.NORMAL,
                wheelchair = p[Keys.wheelchair] ?: false,
                scheduleOnMetered = p[Keys.metered] ?: false,
                ftpFallback = p[Keys.ftp] ?: true,
                showVehicles = p[Keys.vehicles] ?: true,
                startTab = p[Keys.startTab]?.let { runCatching { StartTab.valueOf(it) }.getOrNull() } ?: StartTab.PLAN,
                showStops = p[Keys.stops] ?: true,
                locateButton = p[Keys.locate]?.let { runCatching { LocateButtonPosition.valueOf(it) }.getOrNull() } ?: LocateButtonPosition.BOTTOM_END,
            )
        }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setTheme(value: ThemeMode) { store.edit { it[Keys.theme] = value.name } }
    suspend fun setDynamicColor(value: Boolean) { store.edit { it[Keys.dynamic] = value } }
    suspend fun setAlertNotifications(value: Boolean) { store.edit { it[Keys.alerts] = value } }
    suspend fun setMobilityLayers(value: Set<MobilityKind>) { store.edit { it[Keys.layers] = value.joinToString(",") { k -> k.name } } }
    suspend fun setWalkingSpeed(value: WalkingSpeed) { store.edit { it[Keys.walking] = value.name } }
    suspend fun setWheelchair(value: Boolean) { store.edit { it[Keys.wheelchair] = value } }
    suspend fun setScheduleOnMetered(value: Boolean) { store.edit { it[Keys.metered] = value } }
    suspend fun setFtpFallback(value: Boolean) { store.edit { it[Keys.ftp] = value } }
    suspend fun setShowVehicles(value: Boolean) { store.edit { it[Keys.vehicles] = value } }
    suspend fun setStartTab(value: StartTab) { store.edit { it[Keys.startTab] = value.name } }
    suspend fun setShowStops(value: Boolean) { store.edit { it[Keys.stops] = value } }
    suspend fun setLocateButton(value: LocateButtonPosition) { store.edit { it[Keys.locate] = value.name } }
}
