package org.southtyrol.transit.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.southtyrol.transit.R

/**
 * Launcher icon variants (generated from icons/ by tools/generate_icons.py). Each one is an
 * activity-alias of MainActivity in the manifest; exactly one alias is enabled at a time.
 */
enum class AppIcon(val alias: String, @StringRes val label: Int, @DrawableRes val preview: Int) {
    FLOWING_FOREST("IconFlowingForest", R.string.icon_flowing_forest, R.drawable.icon_preview_flowing_forest),
    ABSTRACT_SILHOUETTE("IconAbstractSilhouette", R.string.icon_abstract_silhouette, R.drawable.icon_preview_abstract_silhouette),
    DUSK_PEAK("IconDuskPeak", R.string.icon_dusk_peak, R.drawable.icon_preview_dusk_peak),
    NIGHT_ROUTE("IconNightRoute", R.string.icon_night_route, R.drawable.icon_preview_night_route),
    SUNRISE_BASIN("IconSunriseBasin", R.string.icon_sunrise_basin, R.drawable.icon_preview_sunrise_basin),
    TEAL_VALLEY("IconTealValley", R.string.icon_teal_valley, R.drawable.icon_preview_teal_valley);

    companion object {
        val DEFAULT = FLOWING_FOREST
    }
}

object AppIcons {
    // Aliases are declared relative to the manifest namespace, which stays fixed even if the
    // applicationId changes.
    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "org.southtyrol.transit." + icon.alias)

    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull { icon ->
            when (pm.getComponentEnabledSetting(component(context, icon))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
                else -> false
            }
        } ?: AppIcon.DEFAULT
    }

    /** Enables [icon]'s alias first, then disables the others, so the app never lacks a launcher entry. */
    fun set(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (other in AppIcon.entries) if (other != icon) {
            pm.setComponentEnabledSetting(component(context, other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
