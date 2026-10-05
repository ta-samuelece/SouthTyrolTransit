package org.southtyrol.transit

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the splash up until settings are read, so the chosen start tab opens directly.
        var initial: UserSettings? = null
        installSplashScreen().setKeepOnScreenCondition { initial == null }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
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
            TransitTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                CompositionLocalProvider(LocalMapStyle provides defaultStyle, LocalDarkTheme provides dark) {
                    TransitApp(startTab = start.startTab)
                }
            }
        }
    }
}

/** Effective dark mode (user setting or system), e.g. for choosing the map style. */
val LocalDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }
