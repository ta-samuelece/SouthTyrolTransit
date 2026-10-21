@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.ScheduleStatus
import org.southtyrol.transit.data.ScheduleRepository
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.StartTab
import org.southtyrol.transit.data.ThemeMode
import org.southtyrol.transit.data.UserSettings
import org.southtyrol.transit.design.ConnectedToggleGroup
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.feature.common.groupShape
import org.southtyrol.transit.work.BackgroundWork
import org.southtyrol.transit.work.Notifications
import javax.inject.Inject

/** Decides whether the first-run screens show: only on a fresh install that has not finished them yet. */
object OnboardingGate {
    /**
     * The settings to start with. An install that was updated (not freshly installed) predates the first-run
     * screens, so it is marked done instead of greeting a long-time user with them.
     */
    suspend fun resolve(settings: SettingsRepository, context: Context): UserSettings {
        val current = settings.current()
        if (current.onboardingDone || isFreshInstall(context)) return current
        settings.setOnboardingDone(true)
        return current.copy(onboardingDone = true)
    }

    private fun isFreshInstall(context: Context): Boolean {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull() ?: return true
        return info.firstInstallTime == info.lastUpdateTime
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val timetable: ScheduleRepository,
    private val work: BackgroundWork,
) : ViewModel() {
    val prefs: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val schedule = timetable.info
    val status = timetable.status

    init { viewModelScope.launch { timetable.loadInfo() } }

    fun alerts(value: Boolean) = viewModelScope.launch { settings.setAlertNotifications(value); work.setAlertChecks(value) }

    /** Also re-registers the download, so a choice of mobile data starts it right away. */
    fun metered(value: Boolean) = viewModelScope.launch {
        settings.setScheduleOnMetered(value)
        work.scheduleTimetableRefresh(allowMetered = value, initialSync = !timetable.downloaded())
    }

    fun startTab(value: StartTab) = viewModelScope.launch { settings.setStartTab(value) }
    fun theme(value: ThemeMode) = viewModelScope.launch { settings.setTheme(value) }
    fun dynamic(value: Boolean) = viewModelScope.launch { settings.setDynamicColor(value) }
    fun amoled(value: Boolean) = viewModelScope.launch { settings.setAmoledDark(value) }
    fun wheelchair(value: Boolean) = viewModelScope.launch { settings.setWheelchair(value) }
    fun finish() = viewModelScope.launch { settings.setOnboardingDone(true) }
}

/** The steps, in order; all but [DONE] count towards the progress bar. */
enum class OnboardingStep { WELCOME, LOCATION, NOTIFICATIONS, TIMETABLE, PREFERENCES, DONE }

private val counted = OnboardingStep.entries.size - 1

/**
 * First-run screens: language, location, notifications, the offline timetable and a few preferences,
 * then a summary. Every choice is saved as it is made, so leaving halfway keeps what was chosen; the app
 * opens once [OnboardingViewModel.finish] marks them done.
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val info by viewModel.schedule.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Saveable: picking a language recreates the activity, and the user must land on the same step.
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[stepIndex]
    val next = { stepIndex = (stepIndex + 1).coerceAtMost(OnboardingStep.DONE.ordinal) }
    var hasLocation by remember { mutableStateOf(locationGranted(context)) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result.values.any { it }
        next()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.alerts(granted)
        next()
    }
    BackHandler(enabled = stepIndex > 0) { stepIndex-- }

    OnboardingContent(
        step = step,
        onBack = { stepIndex-- },
        onSkip = { stepIndex = OnboardingStep.DONE.ordinal },
    ) {
        when (step) {
            OnboardingStep.WELCOME -> WelcomeStep(onNext = next)
            OnboardingStep.LOCATION -> LocationStep(
                granted = hasLocation,
                onAllow = { if (hasLocation) next() else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                onNotNow = next,
            )
            OnboardingStep.NOTIFICATIONS -> NotificationsStep(
                onAllow = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(context)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else { viewModel.alerts(true); next() }
                },
                onNotNow = { viewModel.alerts(false); next() },
            )
            OnboardingStep.TIMETABLE -> TimetableStep(prefs.scheduleOnMetered) { metered -> viewModel.metered(metered); next() }
            OnboardingStep.PREFERENCES -> PreferencesStep(prefs, viewModel, onNext = next)
            OnboardingStep.DONE -> DoneStep(
                prefs = prefs,
                hasLocation = hasLocation,
                notices = prefs.alertNotifications && Notifications.canPost(context),
                timetableReady = info != null,
                status = status,
                onStart = { viewModel.finish() },
            )
        }
    }
}

private fun locationGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The frame every step shares: back, progress and skip on top, the step's content below. */
@Composable
fun OnboardingContent(step: OnboardingStep, onBack: () -> Unit, onSkip: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (step != OnboardingStep.DONE) {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step != OnboardingStep.WELCOME) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                    } else Spacer(Modifier.size(48.dp))
                    val stepText = stringResource(R.string.onboarding_step, step.ordinal + 1, counted)
                    val progress by animateFloatAsState((step.ordinal + 1f) / counted, label = "onboardingProgress")
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).semantics { stateDescription = stepText },
                    )
                    TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_skip)) }
                }
            }
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** Scrolling body (glyph, title, text, the step's own items) above the step's buttons. */
@Composable
private fun ColumnScope.StepLayout(
    icon: ImageVector?,
    title: String,
    body: String,
    centered: Boolean = false,
    buttons: @Composable ColumnScope.() -> Unit,
    items: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = if (centered) 64.dp else 20.dp, bottom = 16.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(112.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(if (icon != null) 16.dp else 8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A grouped list: each item on its own segment, rounded outside, nearly square between items. */
@Composable
private fun Grouped(count: Int, modifier: Modifier = Modifier, item: @Composable (index: Int, shape: androidx.compose.ui.graphics.Shape) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { item(it, groupShape(it, count)) }
    }
}

/** One segment of a grouped list; [selected] gives it the emphasis container. */
@Composable
private fun Segment(shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun TextPair(title: String, hint: String?, modifier: Modifier = Modifier, hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = hintColor)
    }
}

// --- Welcome and language ---

private val languages = listOf(AppLanguage.Option.GERMAN, AppLanguage.Option.ITALIAN, AppLanguage.Option.ENGLISH)

/** The listed language the phone itself uses; English, the app's fallback, when it is none of them. */
private fun phoneLanguage(): AppLanguage.Option {
    val tag = android.content.res.Resources.getSystem().configuration.locales[0]?.language
    return languages.firstOrNull { it.tags.substringBefore(',') == tag } ?: AppLanguage.Option.ENGLISH
}

private fun languageLabel(option: AppLanguage.Option) = when (option) {
    AppLanguage.Option.SYSTEM -> R.string.language_system
    AppLanguage.Option.ENGLISH -> R.string.language_english
    AppLanguage.Option.GERMAN -> R.string.language_german
    AppLanguage.Option.ITALIAN -> R.string.language_italian
}

/** The language in effect: the app's own choice, or the phone's when it follows the system. */
private fun effectiveLanguage(): AppLanguage.Option = AppLanguage.selected().takeIf { it != AppLanguage.Option.SYSTEM } ?: phoneLanguage()

@Composable
private fun ColumnScope.WelcomeStep(onNext: () -> Unit) {
    val phone = remember { phoneLanguage() }
    var selected by remember { mutableStateOf(effectiveLanguage()) }
    StepLayout(
        icon = Icons.Rounded.DirectionsBus,
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
        buttons = { PrimaryButton(stringResource(R.string.onboarding_get_started), onNext) },
    ) {
        GroupTitle(stringResource(R.string.onboarding_language))
        Grouped(languages.size, Modifier.selectableGroup()) { index, shape ->
            val option = languages[index]
            val isSelected = option == selected
            Segment(shape, selected = isSelected) {
                Row(
                    Modifier.heightIn(min = 56.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            if (option == selected) return@selectable
                            selected = option
                            // Back to the phone's language means following the system again. Either way the
                            // activity recreates in the new language and the step survives.
                            AppLanguage.select(if (option == phone) AppLanguage.Option.SYSTEM else option)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    TextPair(stringResource(languageLabel(option)), null, Modifier.weight(1f))
                    if (option == phone) Text(stringResource(R.string.onboarding_phone_language), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// --- Location ---

@Composable
private fun ColumnScope.LocationStep(granted: Boolean, onAllow: () -> Unit, onNotNow: () -> Unit) {
    StepLayout(
        icon = Icons.Rounded.LocationOn,
        title = stringResource(R.string.onboarding_location_title),
        body = stringResource(R.string.onboarding_location_body),
        buttons = {
            if (granted) PrimaryButton(stringResource(R.string.onboarding_continue), onNotNow)
            else {
                PrimaryButton(stringResource(R.string.onboarding_location_allow), onAllow)
                SecondaryButton(stringResource(R.string.onboarding_not_now), onNotNow)
            }
        },
    ) {
        Spacer(Modifier.height(28.dp))
        val benefits = listOf(
            Triple(Icons.Rounded.NearMe, R.string.onboarding_location_nearby, R.string.onboarding_location_nearby_hint),
            Triple(Icons.AutoMirrored.Rounded.DirectionsWalk, R.string.onboarding_location_journeys, R.string.onboarding_location_journeys_hint),
            Triple(Icons.Rounded.Map, R.string.onboarding_location_map, R.string.onboarding_location_map_hint),
        )
        BenefitList(benefits)
        Text(
            stringResource(R.string.onboarding_location_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
        )
    }
}

@Composable
private fun BenefitList(benefits: List<Triple<ImageVector, Int, Int>>) {
    Grouped(benefits.size) { index, shape ->
        val (icon, title, hint) = benefits[index]
        Segment(shape) {
            Row(
                Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                TextPair(stringResource(title), stringResource(hint))
            }
        }
    }
}

// --- Notifications ---

@Composable
private fun ColumnScope.NotificationsStep(onAllow: () -> Unit, onNotNow: () -> Unit) {
    StepLayout(
        icon = Icons.Rounded.Notifications,
        title = stringResource(R.string.onboarding_notifications_title),
        body = stringResource(R.string.onboarding_notifications_body),
        buttons = {
            PrimaryButton(stringResource(R.string.onboarding_notifications_allow), onAllow)
            SecondaryButton(stringResource(R.string.onboarding_not_now), onNotNow)
        },
    ) {
        Text(
            stringResource(R.string.onboarding_example), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 28.dp, bottom = 8.dp),
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                TextPair(
                    stringResource(R.string.onboarding_notifications_example_title),
                    stringResource(R.string.onboarding_notifications_example_body),
                    hintColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Surface(shape = groupShape(0, 1), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(20.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_alert_notifications_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- Offline timetable ---

@Composable
private fun ColumnScope.TimetableStep(meteredSaved: Boolean, onContinue: (metered: Boolean) -> Unit) {
    var metered by rememberSaveable { mutableStateOf(meteredSaved) }
    StepLayout(
        icon = Icons.Rounded.CloudDownload,
        title = stringResource(R.string.onboarding_timetable_title),
        body = stringResource(R.string.onboarding_timetable_body),
        buttons = { PrimaryButton(stringResource(R.string.onboarding_continue)) { onContinue(metered) } },
    ) {
        GroupTitle(stringResource(R.string.onboarding_timetable_choice))
        val options = listOf(
            Triple(false, Icons.Rounded.Wifi, R.string.onboarding_timetable_wifi to R.string.onboarding_timetable_wifi_hint),
            Triple(true, Icons.Rounded.SignalCellularAlt, R.string.onboarding_timetable_any to R.string.onboarding_timetable_any_hint),
        )
        Grouped(options.size, Modifier.selectableGroup()) { index, shape ->
            val (value, icon, texts) = options[index]
            val isSelected = metered == value
            Segment(shape, selected = isSelected) {
                Row(
                    Modifier.heightIn(min = 72.dp).selectable(selected = isSelected, role = Role.RadioButton) { metered = value }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(icon, contentDescription = null)
                    TextPair(stringResource(texts.first), stringResource(texts.second), Modifier.weight(1f), hintColor = Color.Unspecified)
                    RadioButton(selected = isSelected, onClick = null)
                }
            }
        }
    }
}

// --- Preferences ---

private val startTabs = listOf(StartTab.PLAN, StartTab.MAP, StartTab.ALERTS)

private fun startTabLabel(tab: StartTab) = when (tab) {
    StartTab.PLAN -> R.string.nav_plan
    StartTab.DEPARTURES, StartTab.MAP -> R.string.nav_map
    StartTab.ALERTS -> R.string.nav_alerts
    StartTab.SETTINGS -> R.string.nav_settings
}

@Composable
private fun ColumnScope.PreferencesStep(prefs: UserSettings, viewModel: OnboardingViewModel, onNext: () -> Unit) {
    StepLayout(
        icon = null,
        title = stringResource(R.string.onboarding_preferences_title),
        body = stringResource(R.string.onboarding_preferences_body),
        buttons = { PrimaryButton(stringResource(R.string.onboarding_finish), onNext) },
    ) {
        GroupTitle(stringResource(R.string.settings_start_tab))
        ConnectedToggleGroup(
            options = startTabs, selected = prefs.startTab.takeIf { it in startTabs } ?: StartTab.PLAN, onSelect = { viewModel.startTab(it) },
            label = { stringResource(startTabLabel(it)) },
            uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        )
        GroupTitle(stringResource(R.string.onboarding_theme))
        ConnectedToggleGroup(
            options = ThemeMode.entries, selected = prefs.theme, onSelect = { viewModel.theme(it) },
            label = { stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }) },
            icon = { when (it) { ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto; ThemeMode.LIGHT -> Icons.Rounded.LightMode; ThemeMode.DARK -> Icons.Rounded.DarkMode } },
            uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // Same rules as Settings: wallpaper colours exist from Android 12, pure black only where dark mode can apply.
        val switches = buildList<@Composable (androidx.compose.ui.graphics.Shape) -> Unit> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add { shape ->
                SwitchSegment(shape, stringResource(R.string.settings_dynamic_color), null, prefs.dynamicColor) { viewModel.dynamic(it) }
            }
            if (prefs.theme != ThemeMode.LIGHT) add { shape ->
                SwitchSegment(shape, stringResource(R.string.settings_amoled_dark), stringResource(R.string.settings_amoled_dark_hint), prefs.amoledDark) { viewModel.amoled(it) }
            }
            add { shape ->
                SwitchSegment(shape, stringResource(R.string.planner_wheelchair), stringResource(R.string.settings_wheelchair_default), prefs.wheelchair) { viewModel.wheelchair(it) }
            }
        }
        Grouped(switches.size) { index, shape -> switches[index](shape) }
    }
}

@Composable
private fun SwitchSegment(shape: androidx.compose.ui.graphics.Shape, label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Segment(shape) {
        Row(
            Modifier.heightIn(min = 72.dp).toggleable(value = checked, onValueChange = onChange, role = Role.Switch).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextPair(label, hint, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

// --- Done ---

@Composable
private fun ColumnScope.DoneStep(
    prefs: UserSettings,
    hasLocation: Boolean,
    notices: Boolean,
    timetableReady: Boolean,
    status: ScheduleStatus,
    onStart: () -> Unit,
) {
    StepLayout(
        icon = Icons.Rounded.Check,
        title = stringResource(R.string.onboarding_done_title),
        body = stringResource(R.string.onboarding_done_body),
        centered = true,
        buttons = { PrimaryButton(stringResource(R.string.onboarding_start), onStart) },
    ) {
        Spacer(Modifier.height(32.dp))
        val timetable = when {
            status is ScheduleStatus.Downloading -> stringResource(R.string.onboarding_timetable_downloading)
            status is ScheduleStatus.Importing -> stringResource(R.string.onboarding_timetable_installing)
            timetableReady -> stringResource(R.string.onboarding_timetable_ready)
            prefs.scheduleOnMetered -> stringResource(R.string.onboarding_timetable_soon)
            else -> stringResource(R.string.onboarding_timetable_waiting)
        }
        val rows = listOf(
            R.string.onboarding_summary_language to stringResource(languageLabel(effectiveLanguage())),
            R.string.onboarding_summary_location to stringResource(if (hasLocation) R.string.onboarding_allowed else R.string.onboarding_not_allowed),
            R.string.onboarding_summary_notices to stringResource(if (notices) R.string.onboarding_on else R.string.onboarding_off),
            R.string.onboarding_summary_opens to stringResource(startTabLabel(prefs.startTab.takeIf { it in startTabs } ?: StartTab.PLAN)),
            R.string.onboarding_summary_timetable to timetable,
        )
        Grouped(rows.size) { index, shape ->
            val (label, value) = rows[index]
            Segment(shape) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.heightIn(min = 40.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // The timetable row shows the download as it runs.
                    if (label == R.string.onboarding_summary_timetable) when (status) {
                        is ScheduleStatus.Downloading ->
                            if (status.total > 0) LinearProgressIndicator(progress = { status.bytes.toFloat() / status.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        is ScheduleStatus.Importing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        else -> Unit
                    }
                }
            }
        }
    }
}
