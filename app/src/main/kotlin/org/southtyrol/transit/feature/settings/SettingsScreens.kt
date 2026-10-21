@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.BuildConfig
import org.southtyrol.transit.R
import org.southtyrol.transit.data.ScheduleStatus
import org.southtyrol.transit.data.ScheduleRepository
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.ThemeMode
import org.southtyrol.transit.data.StartTab
import org.southtyrol.transit.data.LocateButtonPosition
import org.southtyrol.transit.data.SearchBarPosition
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.data.UserSettings
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.model.GtfsTime
import org.southtyrol.transit.model.TicketingProvider
import org.southtyrol.transit.ui.Navigator
import org.southtyrol.transit.work.BackgroundWork
import org.southtyrol.transit.work.Notifications
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val timetable: ScheduleRepository,
    private val work: BackgroundWork,
) : ViewModel() {
    val prefs: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val schedule = timetable.info
    val status = timetable.status

    init { viewModelScope.launch { timetable.loadInfo() } }

    fun theme(value: ThemeMode) = viewModelScope.launch { settings.setTheme(value) }
    fun dynamic(value: Boolean) = viewModelScope.launch { settings.setDynamicColor(value) }
    fun amoled(value: Boolean) = viewModelScope.launch { settings.setAmoledDark(value) }
    /** Mirrors the launcher icon into the settings so the phone's backup carries it. */
    fun appIcon(icon: org.southtyrol.transit.ui.AppIcon) = viewModelScope.launch { settings.setAppIcon(icon.name) }
    fun wheelchair(value: Boolean) = viewModelScope.launch { settings.setWheelchair(value) }
    fun ftp(value: Boolean) = viewModelScope.launch { settings.setFtpFallback(value) }
    fun metered(value: Boolean) = viewModelScope.launch { settings.setScheduleOnMetered(value); work.scheduleTimetableRefresh(value) }
    fun downloadNow() = work.downloadTimetableNow()
    fun alerts(value: Boolean) = viewModelScope.launch { settings.setAlertNotifications(value); work.setAlertChecks(value) }
    fun startTab(value: StartTab) = viewModelScope.launch { settings.setStartTab(value) }
    fun showStops(value: Boolean) = viewModelScope.launch { settings.setShowStops(value) }
    fun showVehicles(value: Boolean) = viewModelScope.launch { settings.setShowVehicles(value) }
    fun layer(kind: MobilityKind, on: Boolean) = viewModelScope.launch {
        val current = prefs.value.mobilityLayers
        settings.setMobilityLayers(if (on) current + kind else current - kind)
    }
    fun locateButton(value: LocateButtonPosition) = viewModelScope.launch { settings.setLocateButton(value) }
    fun searchBar(value: SearchBarPosition) = viewModelScope.launch { settings.setSearchBar(value) }
}

@Composable
private fun SubScreen(title: String, navigator: Navigator, showBack: Boolean = true, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), content = content)
    }
}

@Composable
fun SettingsScreen(navigator: Navigator, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val info by viewModel.schedule.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.selected()) }
    val updates: org.southtyrol.transit.update.UpdateViewModel = hiltViewModel()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.alerts(granted) }
    // Which choice dialog (or the app icon sheet) is open, if any.
    var choice by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val dismiss = { choice = null }
    when (choice) {
        CHOICE_START -> ChoiceDialog(
            stringResource(R.string.settings_start_tab), StartTab.entries.filter { it != StartTab.SETTINGS && it != StartTab.DEPARTURES }, prefs.startTab,
            { stringResource(startTabLabel(it)) }, { viewModel.startTab(it); dismiss() }, dismiss,
        )
        CHOICE_LANGUAGE -> ChoiceDialog(
            stringResource(R.string.settings_language), AppLanguage.Option.entries, language,
            { stringResource(languageLabel(it)) }, { language = it; dismiss(); AppLanguage.select(it) }, dismiss,
        )
        CHOICE_LOCATE -> ChoiceDialog(
            stringResource(R.string.settings_locate_button), LocateButtonPosition.entries, prefs.locateButton,
            { stringResource(locateLabel(it)) }, { viewModel.locateButton(it); dismiss() }, dismiss,
        )
        CHOICE_SEARCH_BAR -> ChoiceDialog(
            stringResource(R.string.settings_search_bar), SearchBarPosition.entries, prefs.searchBar,
            { stringResource(searchBarLabel(it)) }, { viewModel.searchBar(it); dismiss() }, dismiss,
        )
        CHOICE_ICON -> androidx.compose.material3.ModalBottomSheet(onDismissRequest = dismiss) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_app_icon), style = MaterialTheme.typography.titleLarge)
                AppIconPicker(onPicked = viewModel::appIcon)
                Text(stringResource(R.string.settings_app_icon_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SubScreen(stringResource(R.string.settings_title), navigator) {
        item {
            SettingsGroup(stringResource(R.string.settings_saved)) {
                item { LinkRow(stringResource(R.string.settings_saved_manage), Icons.Rounded.Star, navigator::saved) }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_general)) {
                item {
                    // Settings is not a tab any more: a stored "open on Settings" shows as Plan, where the app opens.
                    val start = prefs.startTab.takeIf { it != StartTab.SETTINGS } ?: StartTab.PLAN
                    ChoiceRow(stringResource(R.string.settings_start_tab), stringResource(startTabLabel(start))) { choice = CHOICE_START }
                }
                item {
                    ChoiceRow(stringResource(R.string.settings_language), stringResource(languageLabel(language))) { choice = CHOICE_LANGUAGE }
                    Text(stringResource(R.string.settings_ladin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_appearance)) {
                item {
                    // Exclusive choice: a connected button group; unselected buttons take a stronger tone than the
                    // item's surfaceContainer to stay visible.
                    org.southtyrol.transit.design.ConnectedToggleGroup(
                        options = ThemeMode.entries, selected = prefs.theme, onSelect = { viewModel.theme(it) },
                        label = { stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }) },
                        icon = { when (it) { ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto; ThemeMode.LIGHT -> Icons.Rounded.LightMode; ThemeMode.DARK -> Icons.Rounded.DarkMode } },
                        uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) item { SwitchRow(stringResource(R.string.settings_dynamic_color), null, prefs.dynamicColor) { viewModel.dynamic(it) } }
                // Only meaningful when dark mode can apply.
                if (prefs.theme != ThemeMode.LIGHT) item { SwitchRow(stringResource(R.string.settings_amoled_dark), stringResource(R.string.settings_amoled_dark_hint), prefs.amoledDark) { viewModel.amoled(it) } }
                item { AppIconRow(prefs.appIcon) { choice = CHOICE_ICON } }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_map)) {
                item {
                    ChipLabel(stringResource(R.string.settings_map_layers))
                    FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(stringResource(R.string.map_layer_stops), prefs.showStops) { viewModel.showStops(it) }
                        ToggleChip(stringResource(R.string.map_layer_vehicles), prefs.showVehicles) { viewModel.showVehicles(it) }
                        ToggleChip(stringResource(R.string.map_layer_parking), MobilityKind.PARKING in prefs.mobilityLayers) { viewModel.layer(MobilityKind.PARKING, it) }
                        ToggleChip(stringResource(R.string.map_layer_bikes), MobilityKind.BIKE_SHARING in prefs.mobilityLayers) { viewModel.layer(MobilityKind.BIKE_SHARING, it) }
                        ToggleChip(stringResource(R.string.map_layer_cars), MobilityKind.CAR_SHARING in prefs.mobilityLayers) { viewModel.layer(MobilityKind.CAR_SHARING, it) }
                    }
                }
                item { ChoiceRow(stringResource(R.string.settings_search_bar), stringResource(searchBarLabel(prefs.searchBar))) { choice = CHOICE_SEARCH_BAR } }
                item { ChoiceRow(stringResource(R.string.settings_locate_button), stringResource(locateLabel(prefs.locateButton))) { choice = CHOICE_LOCATE } }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_accessibility)) {
                item { SwitchRow(stringResource(R.string.planner_wheelchair), stringResource(R.string.settings_wheelchair_default), prefs.wheelchair) { viewModel.wheelchair(it) } }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_notifications)) {
                item {
                    SwitchRow(stringResource(R.string.settings_alert_notifications), stringResource(R.string.settings_alert_notifications_hint), prefs.alertNotifications) { enable ->
                        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(context)) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else viewModel.alerts(enable)
                    }
                }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_timetable)) {
                item {
                    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val i = info
                        if (i == null) Text(stringResource(R.string.timetable_missing))
                        else {
                            Text(stringResource(R.string.timetable_imported, Format.dateTime(i.importedAt)), style = MaterialTheme.typography.bodyLarge)
                            if (i.firstDate > 0) Text(
                                stringResource(R.string.timetable_valid, Format.date(GtfsTime.fromDateKey(i.firstDate)), Format.date(GtfsTime.fromDateKey(i.lastDate))),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(stringResource(R.string.timetable_counts, i.stops, i.trips), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when (val s = status) {
                            is ScheduleStatus.Downloading -> {
                                Text(stringResource(R.string.timetable_downloading))
                                if (s.total > 0) LinearWavyProgressIndicator(progress = { s.bytes.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                                else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is ScheduleStatus.Importing -> {
                                Text(stringResource(R.string.timetable_importing, s.file))
                                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is ScheduleStatus.Failed -> Text(Format.error(s.error), color = MaterialTheme.colorScheme.error)
                            ScheduleStatus.UpToDate -> Text(stringResource(R.string.timetable_up_to_date))
                            ScheduleStatus.Idle -> Unit
                        }
                        Button(onClick = viewModel::downloadNow, enabled = status !is ScheduleStatus.Downloading && status !is ScheduleStatus.Importing) {
                            Text(stringResource(if (info == null) R.string.timetable_download else R.string.timetable_check_update))
                        }
                        Text(stringResource(R.string.timetable_size_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { SwitchRow(stringResource(R.string.settings_metered), stringResource(R.string.settings_metered_hint), prefs.scheduleOnMetered) { viewModel.metered(it) } }
                item { SwitchRow(stringResource(R.string.settings_ftp), stringResource(R.string.settings_ftp_hint), prefs.ftpFallback) { viewModel.ftp(it) } }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_updates)) {
                item { org.southtyrol.transit.update.UpdateStatus(updates) }
                if (updates.enabled) {
                    item { org.southtyrol.transit.update.UpdateAutoCheck(updates) { label, hint, checked, onChange -> SwitchRow(label, hint, checked, onChange) } }
                    item { org.southtyrol.transit.update.UpdateChannelChoice(updates) }
                }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.settings_more)) {
                item { LinkRow(stringResource(R.string.tickets_title), Icons.Rounded.ConfirmationNumber, navigator::tickets) }
                item { LinkRow(stringResource(R.string.about_title), Icons.Rounded.Info, navigator::about) }
                item { LinkRow(stringResource(R.string.licenses_title), Icons.Rounded.Gavel, navigator::licenses) }
            }
        }
    }
}

private const val CHOICE_START = "start"
private const val CHOICE_LANGUAGE = "language"
private const val CHOICE_LOCATE = "locate"
private const val CHOICE_SEARCH_BAR = "searchBar"
private const val CHOICE_ICON = "icon"

private fun searchBarLabel(position: SearchBarPosition) = when (position) {
    SearchBarPosition.TOP -> R.string.search_bar_top
    SearchBarPosition.BOTTOM -> R.string.search_bar_bottom
}

private fun locateLabel(position: LocateButtonPosition) = when (position) {
    LocateButtonPosition.BOTTOM_END -> R.string.locate_bottom_end
    LocateButtonPosition.BOTTOM_START -> R.string.locate_bottom_start
    LocateButtonPosition.HIDDEN -> R.string.locate_hidden
}

private fun startTabLabel(tab: StartTab) = when (tab) {
    StartTab.PLAN -> R.string.nav_plan
    StartTab.DEPARTURES, StartTab.MAP -> R.string.nav_map
    StartTab.ALERTS -> R.string.nav_alerts
    StartTab.SETTINGS -> R.string.nav_settings
}

/** The items of a [SettingsGroup], each one drawn on its own segment of the group. */
private class SettingsItems {
    val items = mutableListOf<@Composable ColumnScope.() -> Unit>()
    fun item(content: @Composable ColumnScope.() -> Unit) { items += content }
}

/**
 * One settings group: a primary heading above a grouped list - each item on its own container, 2 dp apart,
 * rounded outside and nearly square between items (the expressive segmented list).
 */
@Composable
private fun SettingsGroup(title: String, build: SettingsItems.() -> Unit) {
    val items = SettingsItems().apply(build).items
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, content ->
                androidx.compose.material3.Surface(
                    shape = org.southtyrol.transit.feature.common.groupShape(index, items.size),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
                }
            }
        }
    }
}

@Composable
private fun ChipLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

/** A one-of-many setting as a row showing its current value; tapping opens the choices. */
@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The choices of a [ChoiceRow]: radio buttons; picking one applies it and closes the dialog. */
@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option -> RadioRow(label(option), option == selected) { onSelect(option) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The app icon as a row with the current icon's preview; the variants open in a sheet. [chosen] is the
 * stored choice: it changes when one is picked, which refreshes the preview.
 */
@Composable
private fun AppIconRow(chosen: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val current = remember(chosen) { org.southtyrol.transit.ui.AppIcons.current(context) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(current.preview), contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_app_icon), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(current.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Multiple selection: each chip toggles on its own. */
@Composable
private fun ToggleChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        modifier = Modifier.semantics { role = Role.Checkbox },
    )
}

private fun languageLabel(option: AppLanguage.Option) = when (option) {
    AppLanguage.Option.SYSTEM -> R.string.language_system
    AppLanguage.Option.ENGLISH -> R.string.language_english
    AppLanguage.Option.GERMAN -> R.string.language_german
    AppLanguage.Option.ITALIAN -> R.string.language_italian
    AppLanguage.Option.LADIN -> R.string.language_ladin
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, onValueChange = onChange, role = Role.Switch).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LinkRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
fun AboutScreen(navigator: Navigator) {
    val uri = LocalUriHandler.current
    SubScreen(stringResource(R.string.about_title), navigator) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.about_unofficial), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.about_sources)) }
        val sources = listOf(
            Triple(R.string.source_gtfs, R.string.source_gtfs_detail, "https://opendatahub.com/"),
            Triple(R.string.source_efa, R.string.source_efa_detail, "https://data.civis.bz.it/"),
            Triple(R.string.source_mobility, R.string.source_mobility_detail, "https://opendatahub.com/"),
            Triple(R.string.source_map, R.string.source_map_detail, "https://openfreemap.org/"),
        )
        sources.forEach { (title, detail, link) ->
            item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { uri.openUri(link) }) {
                        Text(link)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.about_privacy)) }
        item { Text(stringResource(R.string.about_privacy_text), style = MaterialTheme.typography.bodyMedium) }
        item { SectionHeader(stringResource(R.string.about_realtime)) }
        item { Text(stringResource(R.string.about_realtime_text), style = MaterialTheme.typography.bodyMedium) }
        item { SectionHeader(stringResource(R.string.licenses_title)) }
        item { Text(stringResource(R.string.about_licenses_text), style = MaterialTheme.typography.bodyMedium) }
        item { LinkRow(stringResource(R.string.licenses_show), Icons.Rounded.Gavel, navigator::licenses) }
        item { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp)) }
    }
}

@Composable
fun TicketsScreen(navigator: Navigator, viewModel: TicketsViewModel = hiltViewModel()) {
    val uri = LocalUriHandler.current
    SubScreen(stringResource(R.string.tickets_title), navigator) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.tickets_headline), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.tickets_explanation), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { uri.openUri(viewModel.ticketing.informationUrl) }) {
                    Text(stringResource(R.string.tickets_open_official))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        item { SectionHeader(stringResource(R.string.tickets_fares_heading)) }
        item { Text(stringResource(R.string.tickets_fares_text), style = MaterialTheme.typography.bodyMedium) }
        item {
            OutlinedButton(onClick = { uri.openUri(viewModel.ticketing.tariffInfoUrl) }, modifier = Modifier.padding(vertical = 8.dp)) { Text(stringResource(R.string.tickets_tariff_info)) }
        }
        item { Text(stringResource(R.string.tickets_not_in_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@HiltViewModel
class TicketsViewModel @Inject constructor(val ticketing: TicketingProvider) : ViewModel()

/** The launcher icon variants as round previews; the selected one is outlined and checked. */
@Composable
private fun AppIconPicker(onPicked: (org.southtyrol.transit.ui.AppIcon) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(org.southtyrol.transit.ui.AppIcons.current(context)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.selectableGroup()) {
        org.southtyrol.transit.ui.AppIcon.entries.forEach { icon ->
            val isSelected = icon == selected
            val label = stringResource(icon.label)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        if (!isSelected) {
                            org.southtyrol.transit.ui.AppIcons.set(context, icon)
                            selected = icon
                            onPicked(icon)
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(icon.preview),
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                            .padding(if (isSelected) 5.dp else 0.dp),
                    )
                    if (isSelected) androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(2.dp).size(16.dp))
                    }
                }
                Text(
                    label, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
