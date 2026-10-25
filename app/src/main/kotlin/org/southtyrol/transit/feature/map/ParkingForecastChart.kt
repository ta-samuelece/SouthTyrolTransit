@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.R
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.model.ParkingForecast
import org.southtyrol.transit.model.ParkingForecasts
import org.southtyrol.transit.model.ParkingOutlook
import org.southtyrol.transit.model.TransitZone
import java.time.Instant

/** Height of a bar for a full car park; the dashed "Full" line sits on top of it. */
private val BarHeight = 120.dp

private enum class BarKind { PAST, NOW, FORECAST }

private data class Bar(val kind: BarKind, val at: Instant, val free: Int, val freeLow: Int, val freeHigh: Int)

/**
 * A car park's next hours as bars of how full it is: measured half hours in grey, now in primary,
 * forecast half hours outlined with a line for their likely range. The words say it in free spaces;
 * tapping a bar shows that half hour.
 */
@Composable
fun ParkingForecastCard(f: ParkingForecast, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val bars = remember(f) {
        f.past.map { Bar(BarKind.PAST, it.at, it.free, it.free, it.free) } +
            Bar(BarKind.NOW, f.now.at, f.now.free, f.now.free, f.now.free) +
            f.forecast.map { Bar(BarKind.FORECAST, it.at, it.free, it.freeLow, it.freeHigh) }
    }
    var picked by rememberSaveable(f.now.at.epochSecond) { mutableIntStateOf(-1) }
    val selected = bars.getOrNull(picked)?.takeIf { it.kind != BarKind.NOW }

    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.parking_forecast_title),
                style = MaterialTheme.typography.titleSmall, color = colors.primary,
                modifier = Modifier.semantics { heading() },
            )
            val outlook = f.outlook
            if (outlook is ParkingOutlook.LikelyFull) {
                StatusBanner(stringResource(R.string.parking_forecast_full, Format.time(outlook.from)), kind = BannerKind.WARNING)
            }

            val caption: String
            val detail: String
            when (selected?.kind) {
                BarKind.PAST -> {
                    caption = stringResource(R.string.parking_forecast_measured_at, Format.time(selected.at), selected.free)
                    detail = stringResource(R.string.parking_forecast_measured)
                }
                BarKind.FORECAST -> {
                    caption = stringResource(R.string.parking_forecast_at, Format.time(selected.at), ParkingForecasts.roundFree(selected.free))
                    detail = rangeText(selected)
                }
                else -> {
                    caption = outlookText(outlook)
                    detail = stringResource(R.string.parking_forecast_hint, f.now.free)
                }
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(caption, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }

            Box(Modifier.fillMaxWidth().height(BarHeight + 12.dp)) {
                val line = colors.outline
                Canvas(Modifier.fillMaxWidth().height(2.dp).offset(y = 11.dp)) {
                    drawLine(
                        line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                }
                Text(
                    stringResource(R.string.parking_forecast_full_line, f.capacity),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd).background(colors.surfaceContainer).padding(start = 4.dp),
                )
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(BarHeight),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom,
                ) {
                    bars.forEachIndexed { i, b ->
                        val isSelected = i == picked || (picked < 0 && b.kind == BarKind.NOW)
                        ForecastBar(b, f.capacity, isSelected, barLabel(b), onClick = { picked = i }, modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().offset(y = (-6).dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                bars.forEach { b ->
                    val local = b.at.atZone(TransitZone)
                    val tick = when {
                        b.kind == BarKind.NOW -> stringResource(R.string.parking_forecast_now)
                        local.minute == 0 -> local.hour.toString().padStart(2, '0')
                        else -> ""
                    }
                    // Wider than a bar ("Jetzt", "Now"): centred under it and allowed past its edges.
                    Text(
                        tick, modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true), textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (b.kind == BarKind.NOW) FontWeight.Bold else null,
                        color = if (b.kind == BarKind.NOW) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
            Legend()
        }
    }
}

@Composable
private fun ForecastBar(b: Bar, capacity: Int, selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
    Box(
        modifier.fillMaxHeight().selectable(selected, onClick = onClick, role = Role.Tab).semantics { contentDescription = label },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val base = Modifier.fillMaxWidth().height(heightOf(capacity, b.free))
        val bar = when (b.kind) {
            BarKind.PAST -> base.background(colors.outline, shape)
            BarKind.NOW -> base.background(colors.primary, shape)
            BarKind.FORECAST -> base.background(colors.secondaryContainer, shape).border(1.5.dp, colors.primary, shape)
        }
        Box(if (selected) bar.border(2.5.dp, colors.onSurface, shape) else bar)
        if (b.kind == BarKind.FORECAST && b.freeHigh != b.freeLow) {
            // Bars grow with the cars inside, so the most free spaces are the bottom of the range.
            val bottom = heightOf(capacity, b.freeHigh)
            val top = heightOf(capacity, b.freeLow)
            Box(
                Modifier.padding(bottom = bottom).width(2.dp).height(maxOf(top - bottom, 4.dp))
                    .background(colors.onSecondaryContainer, RoundedCornerShape(1.dp)),
            )
        }
    }
}

private fun heightOf(capacity: Int, free: Int): Dp = BarHeight * ((capacity - free).toFloat() / capacity).coerceIn(0.025f, 1f)

/** For sighted users only: every bar already tells TalkBack what it is. */
@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    val square = Modifier.size(10.dp)
    val shape = RoundedCornerShape(3.dp)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        LegendItem(stringResource(R.string.parking_forecast_legend_past)) { Box(square.background(colors.outline, shape)) }
        LegendItem(stringResource(R.string.parking_forecast_now)) { Box(square.background(colors.primary, shape)) }
        LegendItem(stringResource(R.string.parking_forecast_legend_forecast)) {
            Box(square.background(colors.secondaryContainer, shape).border(1.5.dp, colors.primary, shape))
        }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun outlookText(outlook: ParkingOutlook): String = when (outlook) {
    is ParkingOutlook.FillingUp -> stringResource(R.string.parking_forecast_filling, ParkingForecasts.roundFree(outlook.free), Format.time(outlook.at))
    is ParkingOutlook.Emptying -> stringResource(R.string.parking_forecast_emptying, ParkingForecasts.roundFree(outlook.free), Format.time(outlook.at))
    is ParkingOutlook.LikelyFull -> stringResource(R.string.parking_forecast_full, Format.time(outlook.from))
    ParkingOutlook.Steady -> stringResource(R.string.parking_forecast_steady)
}

@Composable
private fun rangeText(b: Bar): String =
    stringResource(R.string.parking_forecast_range, ParkingForecasts.roundFree(b.freeLow), ParkingForecasts.roundFree(b.freeHigh))

/** What TalkBack reads for a bar: the same words the caption would show for it. */
@Composable
private fun barLabel(b: Bar): String = when (b.kind) {
    BarKind.PAST -> stringResource(R.string.parking_forecast_measured_at, Format.time(b.at), b.free)
    BarKind.NOW -> stringResource(R.string.parking_forecast_now_free, b.free)
    BarKind.FORECAST -> stringResource(R.string.parking_forecast_at, Format.time(b.at), ParkingForecasts.roundFree(b.free)) + ", " + rangeText(b)
}
