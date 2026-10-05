@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.design

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Accessible fallback palette for devices without dynamic color: an alpine teal primary, a warm
 * stone secondary and a sun-amber tertiary. Generated from Material tonal palettes and checked for
 * WCAG AA on every on-/container pair.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF006A62), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E6), onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A6360), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E3), onSecondaryContainer = Color(0xFF051F1D),
    tertiary = Color(0xFF7A5900), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA2), onTertiaryContainer = Color(0xFF261900),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF8), onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8), onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    inverseSurface = Color(0xFF2B3230), inverseOnSurface = Color(0xFFECF2EF), inversePrimary = Color(0xFF82D5CA),
    surfaceDim = Color(0xFFD5DBD9), surfaceBright = Color(0xFFF5FBF8),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF5F2), surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7), surfaceContainerHighest = Color(0xFFDEE4E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D5CA), onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF005049), onPrimaryContainer = Color(0xFF9EF2E6),
    secondary = Color(0xFFB1CCC7), onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF334B48), onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFF2C048), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFFDEA2),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    inverseSurface = Color(0xFFDEE4E1), inverseOnSurface = Color(0xFF2B3230), inversePrimary = Color(0xFF006A62),
    surfaceDim = Color(0xFF0E1513), surfaceBright = Color(0xFF343B39),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF171D1B), surfaceContainer = Color(0xFF1B2120),
    surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF303634),
)

/** True when the user disabled animations system-wide; motion falls back to the standard scheme. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Transit status colors for the active light/dark scheme. */
val LocalStatusColors = staticCompositionLocalOf { StatusColors.Light }

@Composable
fun TransitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val reducedMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalStatusColors provides if (darkTheme) StatusColors.Dark else StatusColors.Light,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = if (reducedMotion) MotionScheme.standard() else MotionScheme.expressive(),
            typography = TransitTypography,
            content = content,
        )
    }
}
