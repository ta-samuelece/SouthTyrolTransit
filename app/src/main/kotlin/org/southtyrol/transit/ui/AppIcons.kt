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
    ALPINE_ROUTE("IconAlpineRoute", R.string.icon_alpine_route, R.drawable.icon_preview_alpine_route),
    GRADIENT_PATH("IconGradientPath", R.string.icon_gradient_path, R.drawable.icon_preview_gradient_path),
    ABSTRACT_SILHOUETTE("IconAbstractSilhouette", R.string.icon_abstract_silhouette, R.drawable.icon_preview_abstract_silhouette),
    FLOWING_FOREST("IconFlowingForest", R.string.icon_flowing_forest, R.drawable.icon_preview_flowing_forest),
    DUSK_PEAK("IconDuskPeak", R.string.icon_dusk_peak, R.drawable.icon_preview_dusk_peak),
    NIGHT_ROUTE("IconNightRoute", R.string.icon_night_route, R.drawable.icon_preview_night_route),
    SUNRISE_BASIN("IconSunriseBasin", R.string.icon_sunrise_basin, R.drawable.icon_preview_sunrise_basin),
    TEAL_VALLEY("IconTealValley", R.string.icon_teal_valley, R.drawable.icon_preview_teal_valley);

    companion object {
        /** The alias enabled in the manifest (`android:enabled="true"`); Abstract Silhouette until 0.1.6,
         *  Gradient Path until 0.1.7-preview.5. */
        val DEFAULT = ALPINE_ROUTE
    }
}

object AppIcons {
    // Aliases are declared relative to the manifest namespace, which stays fixed even if the
    // applicationId changes.
    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "org.southtyrol.transit." + icon.alias)

    /** The icon the user chose (an alias enabled explicitly), else the manifest default. */
    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull { pm.getComponentEnabledSetting(component(context, it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            ?: AppIcon.DEFAULT
    }

    /**
     * A choice made before [AppIcon.DEFAULT] existed disabled every alias of its time but not the default's,
     * which is enabled in the manifest: two launcher entries. Disables the default when another icon is
     * explicitly chosen. Runs at every start; a no-op once settled.
     */
    fun reconcile(context: Context) {
        val pm = context.packageManager
        val chosen = current(context)
        val default = component(context, AppIcon.DEFAULT)
        if (chosen != AppIcon.DEFAULT && pm.getComponentEnabledSetting(default) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            pm.setComponentEnabledSetting(default, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
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
