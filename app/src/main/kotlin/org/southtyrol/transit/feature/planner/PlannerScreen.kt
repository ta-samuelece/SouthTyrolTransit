@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.planner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibleForward
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.rounded.Settings
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import org.southtyrol.transit.design.CycleButton
import org.southtyrol.transit.design.LocalReducedMotion
import org.southtyrol.transit.design.TopLip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.southtyrol.transit.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import org.southtyrol.transit.data.Codec
import org.southtyrol.transit.data.SavedItem
import org.southtyrol.transit.feature.common.PlaceEditorSheet
import org.southtyrol.transit.feature.common.PlaceGlyph
import org.southtyrol.transit.feature.common.PlaceSearchSheet
import org.southtyrol.transit.feature.common.savedPlaceLabel
import org.southtyrol.transit.feature.common.groupGap
import org.southtyrol.transit.feature.common.groupedItemColors
import org.southtyrol.transit.data.ScheduleStatus
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.ConnectedToggleGroup
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.icon
import org.southtyrol.transit.feature.common.rememberLocationPermission
import org.southtyrol.transit.feature.common.showLocationDenied
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.RoutePreference
import org.southtyrol.transit.model.SearchOptions
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.WalkingSpeed
import org.southtyrol.transit.ui.Navigator
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.FilledTonalButton
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Composable
fun PlannerScreen(navigator: Navigator, viewModel: PlannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchField by viewModel.searchField.collectAsStateWithLifecycle()
    val recentJourneys by viewModel.recentJourneys.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val savedJourneys by viewModel.savedJourneys.collectAsStateWithLifecycle()
    val scheduleInfo by viewModel.scheduleInfo.collectAsStateWithLifecycle()
    val scheduleStatus by viewModel.scheduleStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingLocationField by rememberSaveable { mutableStateOf<Field?>(null) }
    var addingPlace by rememberSaveable { mutableStateOf(false) }
    // The place picked for "Add place", encoded so it survives recreation; its editor is open while set.
    var newPlace by rememberSaveable { mutableStateOf<String?>(null) }
    // A tapped saved place waiting for the location permission, encoded like newPlace.
    var pendingPlace by rememberSaveable { mutableStateOf<String?>(null) }
    val requestLocation = rememberLocationPermission { granted ->
        val place = pendingPlace?.let { Codec.place(it) }
        if (place != null) {
            pendingPlace = null
            if (granted) viewModel.planFromHere(place) { viewModel.request()?.let(navigator::results) } else viewModel.locationDenied()
            return@rememberLocationPermission
        }
        val field = pendingLocationField ?: return@rememberLocationPermission
        if (granted) viewModel.useCurrentLocation(field) else viewModel.locationDenied()
    }
    val useLocation: (Field) -> Unit = { field -> pendingPlace = null; pendingLocationField = field; requestLocation() }

    val deniedText = stringResource(R.string.location_denied)
    val unavailableText = stringResource(R.string.location_unavailable)
    val settingsText = stringResource(R.string.location_open_settings)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.locationError) {
        val error = state.locationError ?: return@LaunchedEffect
        if (error is LocationResult.PermissionDenied) snackbar.showLocationDenied(deniedText, settingsText, context)
        else snackbar.showSnackbar(unavailableText)
        viewModel.clearLocationError()
    }

    var showOptions by rememberSaveable { mutableStateOf(false) }
    // Back closes the open lip before it leaves the screen, like a touch outside it.
    BackHandler(enabled = showOptions) { showOptions = false }
    // The app's name greets the view for a moment, then gives way to the question; at once with animations off.
    val reducedMotion = LocalReducedMotion.current
    var introDone by rememberSaveable { mutableStateOf(reducedMotion) }
    LaunchedEffect(Unit) {
        if (!introDone) {
            delay(INTRO_MILLIS)
            introDone = true
        }
    }
    val (title, titleLevel) = when {
        showOptions -> stringResource(R.string.planner_options) to 2
        introDone -> stringResource(R.string.planner_headline) to 1
        else -> stringResource(R.string.app_name) to 0
    }
    Scaffold(
        // The lip reaches under the status bar itself.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        TopLip(
            title = title, titleLevel = titleLevel,
            start = {
                IconToggleButton(
                    checked = showOptions, onCheckedChange = { showOptions = it },
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primary, checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.planner_options)) }
            },
            end = {
                IconButton(onClick = navigator::settings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            },
            expanded = showOptions,
            onDismiss = { showOptions = false },
            expandedContent = { RoutingOptions(state, viewModel) },
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    RouteHero(
                        from = state.from, to = state.to, locating = state.locating,
                        onFrom = { viewModel.openSearch(Field.FROM) }, onTo = { viewModel.openSearch(Field.TO) }, onSwap = viewModel::swap,
                    )
                }
                if (state.time != null) item {
                    ConnectedToggleGroup(
                        options = listOf(false, true), selected = state.arriveBy, onSelect = viewModel::setArriveBy,
                        label = { stringResource(if (it) R.string.planner_arrive_by else R.string.planner_depart_at) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.request()?.let(navigator::results) },
                            enabled = state.from != null && state.to != null,
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.planner_search), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DepartureButton(state, viewModel)
                    }
                }
                if (scheduleInfo == null) item {
                    val downloading = scheduleStatus is ScheduleStatus.Downloading || scheduleStatus is ScheduleStatus.Importing
                    StatusBanner(
                        text = stringResource(if (downloading) R.string.timetable_downloading else R.string.timetable_missing),
                        kind = BannerKind.INFO,
                        action = if (downloading) null else stringResource(R.string.timetable_open_settings),
                        onAction = navigator::settings,
                    )
                }
                item(key = "places") {
                    YourPlaces(
                        places = savedPlaces,
                        onGo = { place -> pendingPlace = Codec.place(place); requestLocation() },
                        onArrive = viewModel::arriveAt,
                        onStartHere = viewModel::startFrom,
                        onAdd = { addingPlace = true },
                    )
                }
                // Each group is one item: the list's 12 dp spacing belongs between form blocks, not between rows.
                val saved = savedJourneys.mapNotNull { item -> item.route?.let { item.label to it } }
                if (saved.isNotEmpty()) item(key = "saved") {
                    RouteGroup(stringResource(R.string.planner_saved_journeys), Icons.Rounded.Bookmark, saved) { viewModel.useRoute(it.first, it.second) }
                }
                val recent = recentJourneys.mapNotNull { item -> item.route?.let { item.label to it } }
                if (recent.isNotEmpty()) item(key = "recent") {
                    RouteGroup(stringResource(R.string.planner_recent), Icons.Rounded.History, recent) { viewModel.useRoute(it.first, it.second) }
                }
            }
        }
    }

    searchField?.let { field ->
        PlaceSearchSheet(
            hint = stringResource(if (field == Field.FROM) R.string.search_from_hint else R.string.search_to_hint),
            onChoose = viewModel::choose,
            onDismiss = viewModel::closeSearch,
            onUseLocation = { useLocation(field) },
        )
    }
    if (addingPlace) {
        PlaceSearchSheet(
            hint = stringResource(R.string.place_add_hint),
            onChoose = { newPlace = Codec.place(it); addingPlace = false },
            onDismiss = { addingPlace = false },
            withSaved = false,
        )
    }
    newPlace?.let { Codec.place(it) }?.let { place -> PlaceEditorSheet(place, savedPlaces.firstOrNull { it.isPlace(place) }, onDismiss = { newPlace = null }) }
}

/** The connected From/To pair: one grouped container whose halves morph their corners when pressed. */
@Composable
private fun RouteHero(from: Place?, to: Place?, locating: Field?, onFrom: () -> Unit, onTo: () -> Unit, onSwap: () -> Unit) {
    Box {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(6.dp)) {
                EndpointField(stringResource(R.string.planner_from), from, Icons.Rounded.TripOrigin, top = true, loading = locating == Field.FROM, onClick = onFrom)
                Spacer(Modifier.height(4.dp))
                EndpointField(stringResource(R.string.planner_to), to, Icons.Rounded.LocationOn, top = false, loading = locating == Field.TO, onClick = onTo)
            }
        }
        FilledTonalIconButton(
            onClick = onSwap,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.planner_swap)) }
    }
}

@Composable
private fun EndpointField(label: String, place: Place?, icon: ImageVector, top: Boolean, loading: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed) 28.dp else 22.dp, label = "outer")
    val inner by animateDpAsState(if (pressed) 22.dp else 6.dp, label = "inner")
    val shape = if (top) RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
    else RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    val value = place?.let { displayName(it) }
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { role = Role.Button; stateDescription = value ?: "" },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 76.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) {
                    Text(stringResource(R.string.location_finding), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(
                        value ?: stringResource(R.string.planner_choose),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun displayName(place: Place): String = when {
    place.type == PlaceType.COORDINATE && place.name.isBlank() -> stringResource(R.string.location_current)
    place.type == PlaceType.COORDINATE -> stringResource(R.string.location_current_near, place.name)
    else -> place.name
}

/**
 * The one departure control, beside Find connections. Leaving now it is the clock icon alone, in the tonal
 * colour; once a time is chosen it widens to show that time and turns primary, so a set time is never missed.
 * A tap opens the date and time pickers; while a time is set, the date picker also offers Leave now to reset it.
 */
@Composable
private fun DepartureButton(state: PlannerState, viewModel: PlannerViewModel) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var chosenDate by rememberSaveable { mutableStateOf<Long?>(null) }
    val time = state.time
    val shape = RoundedCornerShape(24.dp)
    if (time == null) {
        val description = stringResource(R.string.planner_departure_now_description)
        FilledTonalButton(
            onClick = { pickDate = true }, shape = shape, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(64.dp).semantics { contentDescription = description },
        ) { Icon(Icons.Rounded.Schedule, contentDescription = null) }
    } else {
        val locale = LocalConfiguration.current.locales[0]
        val full = "${Format.weekdayDate(time.toLocalDate())}, ${Format.time(time.toInstant())}"
        // Today's time alone; another day gets its short weekday, so the button stays narrow.
        val short = if (time.toLocalDate() == LocalDate.now(TransitZone)) Format.time(time.toInstant())
            else "${time.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${Format.time(time.toInstant())}"
        val description = stringResource(R.string.planner_departure_at_description, full)
        Button(
            onClick = { pickDate = true }, shape = shape, contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
            modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = description },
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(short, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }

    if (pickDate) {
        val initial = (time ?: ZonedDateTime.now(TransitZone)).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        org.southtyrol.transit.feature.common.TransitDatePickerDialog(
            initial, stringResource(R.string.action_next),
            onConfirm = { chosenDate = it; pickDate = false; pickTime = true }, onDismiss = { pickDate = false },
            neutral = if (time == null) null else {
                { TextButton(onClick = { viewModel.setTime(null); pickDate = false }) { Text(stringResource(R.string.planner_leave_now)) } }
            },
        )
    }
    if (pickTime) {
        val now = state.time ?: ZonedDateTime.now(TransitZone)
        val timeState = rememberTimePickerState(now.hour, now.minute)
        TimePickerDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.planner_pick_time)) },
            confirmButton = {
                TextButton(onClick = {
                    val date = chosenDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: LocalDate.now(TransitZone)
                    viewModel.setTime(date.atTime(timeState.hour, timeState.minute).atZone(TransitZone))
                    pickTime = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { TimePicker(timeState) }
    }
}

/**
 * The routing options, opened in the top lip at their full height as cards in the surface tone: the wheelchair
 * and bike switches side by side, then Prefer, Walking and Longest walk as cycle buttons, then the transport
 * modes. The surface tone keeps them apart from the lip's secondary container, where the defaults would vanish.
 */
@Composable
private fun RoutingOptions(state: PlannerState, viewModel: PlannerViewModel) {
    val surface = MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchTile(
            Icons.Rounded.AccessibleForward, stringResource(R.string.planner_wheelchair_short), stringResource(R.string.planner_wheelchair),
            state.wheelchair, viewModel::setWheelchair, Modifier.weight(1f).fillMaxHeight(),
        )
        SwitchTile(
            Icons.Rounded.PedalBike, stringResource(R.string.planner_bike_short), stringResource(R.string.planner_bike),
            state.withBike, viewModel::setWithBike, Modifier.weight(1f).fillMaxHeight(),
        )
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CycleButton(
            label = stringResource(R.string.planner_preference),
            options = RoutePreference.entries.toList(), selected = state.preference, onSelect = viewModel::setPreference,
            valueLabel = { stringResource(preferenceLabel(it)) },
            containerColor = surface, modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        CycleButton(
            label = stringResource(R.string.planner_walking_short), description = stringResource(R.string.planner_walking_speed),
            options = WalkingSpeed.entries.toList(), selected = state.walkingSpeed, onSelect = viewModel::setWalkingSpeed,
            valueLabel = { stringResource(when (it) { WalkingSpeed.SLOW -> R.string.walking_slow; WalkingSpeed.NORMAL -> R.string.walking_normal; WalkingSpeed.FAST -> R.string.walking_fast }) },
            containerColor = surface, modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        CycleButton(
            label = stringResource(R.string.planner_max_walk_short), description = stringResource(R.string.planner_max_walk),
            options = SearchOptions.MAX_WALK_CHOICES, selected = state.maxWalkMinutes, onSelect = viewModel::setMaxWalk,
            valueLabel = { Format.duration(java.time.Duration.ofMinutes(it.toLong())) },
            containerColor = surface, modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
    ModesCard(state, viewModel)
}

/** The transport modes as filter chips in a card; selected chips are primary, like every choice in the lip. */
@Composable
private fun ModesCard(state: PlannerState, viewModel: PlannerViewModel) {
    val colors = MaterialTheme.colorScheme
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = colors.primary, selectedLabelColor = colors.onPrimary, selectedLeadingIconColor = colors.onPrimary,
    )
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.planner_modes), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TransportMode.TRAIN, TransportMode.BUS, TransportMode.CITY_BUS, TransportMode.CABLE_CAR, TransportMode.ON_DEMAND).forEach { mode ->
                    val included = mode !in state.excludedModes
                    FilterChip(
                        selected = included,
                        onClick = { viewModel.toggleMode(mode) },
                        label = { Text(Format.mode(mode)) },
                        leadingIcon = { Icon(mode.icon(), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                        colors = chipColors,
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = included, borderColor = colors.outline),
                    )
                }
            }
        }
    }
}

/**
 * A switch as a half-width tile: the option's icon and the switch on top, its short [label] under them. The whole
 * tile toggles; TalkBack reads the full [description].
 */
@Composable
private fun SwitchTile(icon: ImageVector, label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** How long the app's name holds in the Plan lip before "Where to?" takes over. */
private const val INTRO_MILLIS = 1600L

fun preferenceLabel(p: RoutePreference) = when (p) {
    RoutePreference.FASTEST -> R.string.pref_fastest
    RoutePreference.FEWEST_CHANGES -> R.string.pref_fewest_changes
    RoutePreference.LEAST_WALKING -> R.string.pref_least_walking
}

@Composable
private fun RouteGroup(title: String, icon: ImageVector, routes: List<Pair<String, Pair<Place, Place>>>, onClick: (Pair<Place, Place>) -> Unit) {
    Column {
        SectionHeader(title)
        routes.forEachIndexed { index, (label, route) -> RouteItem(label, icon, index, routes.size) { onClick(route) } }
    }
}

@Composable
private fun RouteItem(label: String, icon: ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count), colors = groupedItemColors(), modifier = Modifier.groupGap(index, count),
        leadingContent = { Icon(icon, contentDescription = null) },
    ) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/**
 * Home, Work and the user's own places as cards of one height, "Add place" included, in one sideways-scrolling
 * row: a tap finds connections from the current location, a long press opens a small FAB-style menu to arrive
 * there or start from there.
 */
@Composable
private fun YourPlaces(places: List<SavedItem>, onGo: (Place) -> Unit, onArrive: (Place) -> Unit, onStartHere: (Place) -> Unit, onAdd: () -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.planner_saved_places))
        // Edge to edge, so the next card peeks out at the screen's edge and the row reads as scrollable. A plain
        // scrolling row, not a lazy one: a handful of cards, and only a row measured as a whole can give the add
        // card the height of the place cards.
        Row(
            Modifier.fillMaxWidth().ignoreHorizontalPadding(ScreenGutter).horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Max).padding(horizontal = ScreenGutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            places.forEach { item ->
                val place = item.place ?: return@forEach
                key(item.id) { PlaceCard(item, place, onClick = { onGo(place) }, onArrive = { onArrive(place) }, onStartHere = { onStartHere(place) }) }
            }
            AddPlaceCard(onAdd)
        }
    }
}

private val PlaceCardWidth = 120.dp

/** The Plan list's side padding, which the places row reaches past. */
private val ScreenGutter = 16.dp

/** Lays the element out [gutter] wider on each side than its parent allows, cancelling the parent's padding. */
private fun Modifier.ignoreHorizontalPadding(gutter: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = (gutter * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun PlaceCard(item: SavedItem, place: Place, onClick: () -> Unit, onArrive: () -> Unit, onStartHere: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val label = savedPlaceLabel(item)
    // Toggled like a FAB while its menu is open.
    val container by animateColorAsState(
        if (menu) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, label = "placeCard",
    )
    Box(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(PlaceCardWidth).fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .combinedClickable(
                    onClick = onClick, onClickLabel = stringResource(R.string.place_go_from_here),
                    onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.place_options, label),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaceGlyph(item)
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(place.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (menu) {
            PlaceMenu(
                listOf(
                    stringResource(R.string.place_arrive_here) to Icons.Rounded.LocationOn,
                    stringResource(R.string.place_start_here) to Icons.Rounded.TripOrigin,
                ),
                onPick = { index -> menu = false; if (index == 0) onArrive() else onStartHere() },
                onDismiss = { menu = false },
            )
        }
    }
}

/**
 * A place card's menu: FAB menu pills dropping in one after another just below the card, or above it
 * where the screen ends; a tap elsewhere closes it.
 */
@Composable
private fun PlaceMenu(items: List<Pair<String, ImageVector>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap) { BelowOrAbove(gap) }
    // Hidden for the first frame, so the pills animate in.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { index, (label, icon) ->
                AnimatedVisibility(
                    shown,
                    enter = fadeIn(tween(120, delayMillis = index * 40)) +
                        scaleIn(tween(180, delayMillis = index * 40), initialScale = 0.6f, transformOrigin = TransformOrigin(0f, 0f)),
                ) {
                    Surface(
                        onClick = { onPick(index) }, shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shadowElevation = 3.dp,
                    ) {
                        Row(Modifier.height(56.dp).padding(start = 16.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Below the anchor, aligned with its start; above it when it does not fit below; always inside the window. */
private class BelowOrAbove(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun AddPlaceCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.width(PlaceCardWidth).fillMaxHeight(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp)) }
            }
            Text(stringResource(R.string.place_add), style = MaterialTheme.typography.titleMedium, maxLines = 2)
        }
    }
}
