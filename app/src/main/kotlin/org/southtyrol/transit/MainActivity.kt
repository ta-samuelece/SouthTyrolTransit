package org.southtyrol.transit

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.ThemeMode
import org.southtyrol.transit.data.UserSettings
import org.southtyrol.transit.design.TransitTheme
import org.southtyrol.transit.map.LocalMapStyle
import org.southtyrol.transit.map.MapStyle
import org.southtyrol.transit.ui.TransitApp
import javax.inject.Inject

/** AppCompatActivity so per-app language selection also works on Android 12 and lower. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    /** A stop to open, e.g. from the home-screen widget; consumed once shown. */
    private val openStop = mutableStateOf<Pair<String, String>?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        readStop(intent)
    }

    private fun readStop(intent: android.content.Intent?) {
        val key = intent?.getStringExtra(EXTRA_STOP_KEY) ?: return
        openStop.value = key to intent.getStringExtra(EXTRA_STOP_NAME).orEmpty()
        intent.removeExtra(EXTRA_STOP_KEY)
    }

    companion object {
        private const val EXTRA_STOP_KEY = "org.southtyrol.transit.STOP_KEY"
        private const val EXTRA_STOP_NAME = "org.southtyrol.transit.STOP_NAME"

        fun openStopIntent(context: android.content.Context, stopKey: String, name: String): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_STOP_KEY, stopKey).putExtra(EXTRA_STOP_NAME, name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Distinct data per stop so each widget's PendingIntent stays separate.
                .setData(android.net.Uri.parse("southtyroltransit://stop/" + android.net.Uri.encode(stopKey)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the splash up until settings are read, so the chosen start tab opens directly.
        var initial: UserSettings? = null
        installSplashScreen().setKeepOnScreenCondition { initial == null }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readStop(intent)
        val defaultStyle = MapStyle(
            lightUrl = BuildConfig.MAP_STYLE_LIGHT.ifBlank { "https://tiles.openfreemap.org/styles/positron" },
            darkUrl = BuildConfig.MAP_STYLE_DARK.ifBlank { BuildConfig.MAP_STYLE_LIGHT.ifBlank { "https://tiles.openfreemap.org/styles/dark" } },
        )
        lifecycleScope.launch { initial = settingsRepository.current() }
        setContent {
            val first = remember { mutableStateOf<UserSettings?>(null) }
            LaunchedEffect(Unit) { first.value = settingsRepository.current() }
            val start = first.value ?: return@setContent
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = start)
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // System bar icons follow the app's theme, not the system's: with the phone in dark mode and the
            // app set to Light, "auto" would keep white icons on a light background.
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            TransitTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                CompositionLocalProvider(LocalMapStyle provides defaultStyle, LocalDarkTheme provides dark) {
                    TransitApp(startTab = start.startTab, openStop = openStop.value, onStopOpened = { openStop.value = null })
                }
            }
        }
    }
}

/** Effective dark mode (user setting or system), e.g. for choosing the map style. */
val LocalDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }
