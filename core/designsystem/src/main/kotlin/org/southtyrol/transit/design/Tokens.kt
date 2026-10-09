package org.southtyrol.transit.design

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.southtyrol.transit.model.ColorContrast
import org.southtyrol.transit.model.TransportMode

private val base = Typography()

/** Tabular figures keep countdowns and timetables from jittering as digits change. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

val TransitTypography = Typography(
    displayLarge = base.displayLarge.tabular(), displayMedium = base.displayMedium.tabular(), displaySmall = base.displaySmall.tabular(),
    headlineLarge = base.headlineLarge.tabular(), headlineMedium = base.headlineMedium.tabular(), headlineSmall = base.headlineSmall.tabular(),
    titleLarge = base.titleLarge, titleMedium = base.titleMedium, titleSmall = base.titleSmall,
    bodyLarge = base.bodyLarge, bodyMedium = base.bodyMedium, bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.tabular(), labelMedium = base.labelMedium.tabular(), labelSmall = base.labelSmall.tabular(),
)

/** Large, confident time styles used for the hero data on cards and boards. */
object TimeStyles {
    val hero = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)
    val large = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val medium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}

@Immutable
data class StatusColors(
    val live: Color,
    val onTime: Color,
    val delayed: Color,
    val early: Color,
    val cancelled: Color,
    val stale: Color,
    val delayedContainer: Color,
    val onDelayedContainer: Color,
    val liveContainer: Color,
    val onLiveContainer: Color,
) {
    companion object {
        val Light = StatusColors(
            live = Color(0xFF1B6C30), onTime = Color(0xFF1B6C30), delayed = Color(0xFF8A4F00), early = Color(0xFF1D5FA8),
            cancelled = Color(0xFFBA1A1A), stale = Color(0xFF6F7977),
            delayedContainer = Color(0xFFFFDCBE), onDelayedContainer = Color(0xFF2D1600),
            liveContainer = Color(0xFFA7F5A9), onLiveContainer = Color(0xFF002107),
        )
        val Dark = StatusColors(
            live = Color(0xFF8CD98F), onTime = Color(0xFF8CD98F), delayed = Color(0xFFFFB872), early = Color(0xFFA6C8FF),
            cancelled = Color(0xFFFFB4AB), stale = Color(0xFF899390),
            delayedContainer = Color(0xFF6A3B00), onDelayedContainer = Color(0xFFFFDCBE),
            liveContainer = Color(0xFF005318), onLiveContainer = Color(0xFFA7F5A9),
        )
    }
}

/** Identity colors per mode for line badges when GTFS provides no route color. */
/**
 * Progress along a run, the same in every view: the stretch already travelled and passed stops are drawn
 * at [PASSED_ALPHA] (timelines, rails, map lines and stop markers).
 */
object Progress {
    const val PASSED_ALPHA = 0.35f
}

object ModeColors {
    fun container(mode: TransportMode): Long = when (mode) {
        TransportMode.TRAIN -> 0x3949AB
        TransportMode.BUS -> 0x00796B
        TransportMode.CITY_BUS -> 0xE65100
        TransportMode.CABLE_CAR, TransportMode.FUNICULAR -> 0x7B1FA2
        TransportMode.TRAM -> 0xC62828
        TransportMode.ON_DEMAND -> 0x5D4037
        TransportMode.WALK -> 0x607D8B
        TransportMode.OTHER -> 0x455A64
    }

    fun colors(mode: TransportMode, color: Long?, textColor: Long?): Pair<Color, Color> {
        val bg = color ?: container(mode)
        val fg = ColorContrast.readableOn(bg, textColor)
        return Color(0xFF000000 or bg) to Color(0xFF000000 or fg)
    }
}

/** Mode-specific badge silhouettes so lines are distinguishable without relying on color. */
object ModeShapes {
    @Composable
    fun badge(mode: TransportMode): Shape = when (mode) {
        TransportMode.TRAIN -> CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
        TransportMode.CITY_BUS -> RoundedCornerShape(8.dp)
        TransportMode.BUS -> RoundedCornerShape(50)
        TransportMode.CABLE_CAR, TransportMode.FUNICULAR -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        TransportMode.ON_DEMAND -> RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
        else -> RoundedCornerShape(6.dp)
    }
}
