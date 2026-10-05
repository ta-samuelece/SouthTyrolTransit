package org.southtyrol.transit.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formatting helpers. All clock times are shown in the network timezone (Europe/Rome) because that
 * is what stop signage and timetables use, regardless of the device timezone.
 */
object Format {
    @Composable
    @ReadOnlyComposable
    fun locale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

    @Composable
    @ReadOnlyComposable
    fun time(instant: Instant): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale()).format(instant.atZone(TransitZone))

    @Composable
    @ReadOnlyComposable
    fun date(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()).format(date)

    @Composable
    @ReadOnlyComposable
    fun dateTime(instant: Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale()).format(instant.atZone(TransitZone))

    @Composable
    @ReadOnlyComposable
    fun weekdayDate(date: LocalDate): String = DateTimeFormatter.ofPattern("EEE d MMM", locale()).format(date)

    @Composable
    @ReadOnlyComposable
    fun duration(d: Duration): String {
        val minutes = d.toMinutes().coerceAtLeast(0)
        return if (minutes >= 60) stringResource(R.string.ds_duration_hm, (minutes / 60).toInt(), (minutes % 60).toInt()) else stringResource(R.string.ds_duration_m, minutes.toInt())
    }

    @Composable
    @ReadOnlyComposable
    fun distance(meters: Double): String = if (meters < 1000) stringResource(R.string.ds_meters, ((meters / 10).roundToInt() * 10)) else stringResource(R.string.ds_kilometers, meters / 1000.0)

    /** Minutes until [target], rounded down; negative values mean "already due". */
    fun minutesUntil(target: Instant, now: Instant): Long = Math.floorDiv(Duration.between(now, target).seconds, 60L)

    @Composable
    @ReadOnlyComposable
    fun countdown(target: Instant, now: Instant): String {
        val minutes = minutesUntil(target, now)
        return when {
            minutes <= 0 -> stringResource(R.string.ds_now)
            minutes < 60 -> pluralStringResource(R.plurals.ds_minutes_short, minutes.toInt(), minutes.toInt())
            else -> time(target)
        }
    }

    @Composable
    @ReadOnlyComposable
    fun age(at: Instant, now: Instant): String {
        val seconds = Duration.between(at, now).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> stringResource(R.string.ds_age_seconds, seconds.toInt())
            seconds < 3600 -> stringResource(R.string.ds_age_minutes, (seconds / 60).toInt())
            seconds < 86_400 -> stringResource(R.string.ds_age_hours, (seconds / 3600).toInt())
            else -> stringResource(R.string.ds_age_days, (seconds / 86_400).toInt())
        }
    }

    @Composable
    @ReadOnlyComposable
    fun mode(mode: TransportMode): String = stringResource(
        when (mode) {
            TransportMode.BUS -> R.string.ds_mode_bus
            TransportMode.CITY_BUS -> R.string.ds_mode_city_bus
            TransportMode.TRAIN -> R.string.ds_mode_train
            TransportMode.CABLE_CAR -> R.string.ds_mode_cable_car
            TransportMode.FUNICULAR -> R.string.ds_mode_funicular
            TransportMode.TRAM -> R.string.ds_mode_tram
            TransportMode.ON_DEMAND -> R.string.ds_mode_on_demand
            TransportMode.WALK -> R.string.ds_mode_walk
            TransportMode.OTHER -> R.string.ds_mode_other
        },
    )

    @Composable
    @ReadOnlyComposable
    fun error(error: DataError): String = when (error) {
        DataError.Offline -> stringResource(R.string.ds_error_offline)
        DataError.Timeout -> stringResource(R.string.ds_error_timeout)
        is DataError.Throttled -> stringResource(R.string.ds_error_throttled)
        is DataError.Server -> stringResource(R.string.ds_error_server, error.status)
        DataError.Parse -> stringResource(R.string.ds_error_parse)
        DataError.NoSchedule -> stringResource(R.string.ds_error_no_schedule)
        DataError.NotFound -> stringResource(R.string.ds_error_not_found)
        is DataError.Planner -> when (error.code) {
            "origin" -> stringResource(R.string.ds_error_origin)
            "destination" -> stringResource(R.string.ds_error_destination)
            "same" -> stringResource(R.string.ds_error_same)
            else -> stringResource(R.string.ds_error_planner)
        }
        DataError.Unknown -> stringResource(R.string.ds_error_unknown)
    }
}
