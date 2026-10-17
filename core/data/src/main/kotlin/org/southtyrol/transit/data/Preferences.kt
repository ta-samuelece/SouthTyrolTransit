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

/**
 * Tab shown when the app opens. DEPARTURES (the old Stops tab, now the map's search) and SETTINGS are no
 * longer tabs; they stay so stored choices still parse: DEPARTURES reads back as MAP, SETTINGS opens Plan.
 */
enum class StartTab { PLAN, DEPARTURES, MAP, ALERTS, SETTINGS }

/** Which GitHub releases the in-app updater offers: only stable ones, or pre-releases ("previews") too. */
enum class UpdateChannel { STABLE, PREVIEW }

/**
 * Where the map's "center on my position" button sits (or whether it is shown at all). The centre position
 * was dropped when the search bar could move to the bottom: a stored BOTTOM_CENTER no longer parses and
 * falls back to the default.
 */
enum class LocateButtonPosition { BOTTOM_END, BOTTOM_START, HIDDEN }

/** Where the map's search bar floats. At the bottom (the default) it shares the row with the locate button. */
enum class SearchBarPosition { TOP, BOTTOM }

data class UserSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default: the app's own palette, unless the user picks the wallpaper colours. */
    val dynamicColor: Boolean = false,
    /** Pure black backgrounds in dark mode (saves power on OLED screens). */
    val amoledDark: Boolean = false,
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
    val searchBar: SearchBarPosition = SearchBarPosition.BOTTOM,
    /** Ask GitHub for a newer release when the app starts (nothing installs without confirmation). */
    val autoUpdateCheck: Boolean = true,
    /** A release version the user chose to skip; not offered again automatically. */
    val skippedUpdate: String = "",
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    /**
     * The launcher icon's name, mirrored from the activity-alias state (which Android does not back
     * up) so a restore can re-apply it. Blank until first recorded.
     */
    val appIcon: String = "",
    /** The first-run screens were finished or skipped (existing installs count as done, see the onboarding gate). */
    val onboardingDone: Boolean = false,
    /** The map has once told the user that a long press on the locate button lists the saved stops. */
    val savedStopsHintShown: Boolean = false,
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
        val amoled = booleanPreferencesKey("amoledDark")
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
        val searchBar = stringPreferencesKey("searchBar")
        val autoUpdate = booleanPreferencesKey("autoUpdateCheck")
        val skipped = stringPreferencesKey("skippedUpdate")
        val channel = stringPreferencesKey("updateChannel")
        val icon = stringPreferencesKey("appIcon")
        val onboarding = booleanPreferencesKey("onboardingDone")
        val savedStopsHint = booleanPreferencesKey("savedStopsHintShown")
    }

    val settings: Flow<UserSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            UserSettings(
                theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                dynamicColor = p[Keys.dynamic] ?: false,
                amoledDark = p[Keys.amoled] ?: false,
                alertNotifications = p[Keys.alerts] ?: false,
                mobilityLayers = p[Keys.layers]?.split(',')?.mapNotNull { runCatching { MobilityKind.valueOf(it) }.getOrNull() }?.toSet() ?: setOf(MobilityKind.PARKING),
                walkingSpeed = p[Keys.walking]?.let { runCatching { WalkingSpeed.valueOf(it) }.getOrNull() } ?: WalkingSpeed.NORMAL,
                wheelchair = p[Keys.wheelchair] ?: false,
                scheduleOnMetered = p[Keys.metered] ?: false,
                ftpFallback = p[Keys.ftp] ?: true,
                showVehicles = p[Keys.vehicles] ?: true,
                startTab = p[Keys.startTab]?.let { runCatching { StartTab.valueOf(it) }.getOrNull() }?.let { if (it == StartTab.DEPARTURES) StartTab.MAP else it } ?: StartTab.PLAN,
                showStops = p[Keys.stops] ?: true,
                locateButton = p[Keys.locate]?.let { runCatching { LocateButtonPosition.valueOf(it) }.getOrNull() } ?: LocateButtonPosition.BOTTOM_END,
                searchBar = p[Keys.searchBar]?.let { runCatching { SearchBarPosition.valueOf(it) }.getOrNull() } ?: SearchBarPosition.BOTTOM,
                autoUpdateCheck = p[Keys.autoUpdate] ?: true,
                skippedUpdate = p[Keys.skipped] ?: "",
                updateChannel = p[Keys.channel]?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() } ?: UpdateChannel.STABLE,
                appIcon = p[Keys.icon] ?: "",
                onboardingDone = p[Keys.onboarding] ?: false,
                savedStopsHintShown = p[Keys.savedStopsHint] ?: false,
            )
        }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setTheme(value: ThemeMode) { store.edit { it[Keys.theme] = value.name } }
    suspend fun setDynamicColor(value: Boolean) { store.edit { it[Keys.dynamic] = value } }
    suspend fun setAmoledDark(value: Boolean) { store.edit { it[Keys.amoled] = value } }
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
    suspend fun setSearchBar(value: SearchBarPosition) { store.edit { it[Keys.searchBar] = value.name } }
    suspend fun setAutoUpdateCheck(value: Boolean) { store.edit { it[Keys.autoUpdate] = value } }
    suspend fun setSkippedUpdate(value: String) { store.edit { it[Keys.skipped] = value } }
    suspend fun setUpdateChannel(value: UpdateChannel) { store.edit { it[Keys.channel] = value.name } }
    suspend fun setAppIcon(value: String) { store.edit { it[Keys.icon] = value } }
    suspend fun setOnboardingDone(value: Boolean) { store.edit { it[Keys.onboarding] = value } }
    suspend fun setSavedStopsHintShown(value: Boolean) { store.edit { it[Keys.savedStopsHint] = value } }
}
