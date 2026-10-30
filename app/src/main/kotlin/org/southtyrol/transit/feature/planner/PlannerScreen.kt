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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.southtyrol.transit.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
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
import org.southtyrol.transit.feature.common.TopLevelBar
import org.southtyrol.transit.feature.common.rememberLocationPermission
import org.southtyrol.transit.feature.common.showLocationDenied
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.RoutePreference
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.WalkingSpeed
import org.southtyrol.transit.ui.Navigator
import java.time.Instant
import java.time.LocalDate
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
    val requestLocation = rememberLocationPermission { granted ->
        val field = pendingLocationField ?: return@rememberLocationPermission
        if (granted) viewModel.useCurrentLocation(field) else viewModel.locationDenied()
    }
    val useLocation: (Field) -> Unit = { field -> pendingLocationField = field; requestLocation() }

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

    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { TopLevelBar(stringResource(R.string.app_name), scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                Text(stringResource(R.string.planner_headline), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                RouteHero(
                    from = state.from, to = state.to, locating = state.locating,
                    onFrom = { viewModel.openSearch(Field.FROM) }, onTo = { viewModel.openSearch(Field.TO) }, onSwap = viewModel::swap,
                )
            }
            item { TimeAndOptions(state, viewModel) }
            item {
                Button(
                    onClick = { viewModel.request()?.let(navigator::results) },
                    enabled = state.from != null && state.to != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.planner_search), style = MaterialTheme.typography.titleMedium)
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
                    onUse = viewModel::useSavedPlace,
                    onAdd = { addingPlace = true },
                    onEdit = { navigator.saved() },
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

@Composable
private fun TimeAndOptions(state: PlannerState, viewModel: PlannerViewModel) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    var chosenDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.time == null,
                onClick = { viewModel.setTime(null) },
                label = { Text(stringResource(R.string.planner_leave_now)) },
                leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
            FilterChip(
                selected = state.time != null,
                onClick = { pickDate = true },
                label = {
                    Text(state.time?.let { "${Format.weekdayDate(it.toLocalDate())}, ${Format.time(it.toInstant())}" } ?: stringResource(R.string.planner_pick_time))
                },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showOptions = !showOptions }) {
                Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.planner_options))
            }
        }
        if (state.time != null) {
            ConnectedToggleGroup(
                options = listOf(false, true), selected = state.arriveBy, onSelect = viewModel::setArriveBy,
                label = { stringResource(if (it) R.string.planner_arrive_by else R.string.planner_depart_at) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(showOptions) {
            // A card of its own, so the extra options read as one block apart from the rest of the form.
            androidx.compose.material3.Card(
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.planner_preference), style = MaterialTheme.typography.labelLarge)
                    ConnectedToggleGroup(
                        options = RoutePreference.entries.toList(), selected = state.preference, onSelect = viewModel::setPreference,
                        label = { stringResource(preferenceLabel(it)) }, modifier = Modifier.fillMaxWidth(),
                        // The card is surfaceContainer, the toggles' default: lift them one tone.
                        uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(stringResource(R.string.planner_modes), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(TransportMode.TRAIN, TransportMode.BUS, TransportMode.CITY_BUS, TransportMode.CABLE_CAR, TransportMode.ON_DEMAND).forEach { mode ->
                            val included = mode !in state.excludedModes
                            FilterChip(
                                selected = included,
                                onClick = { viewModel.toggleMode(mode) },
                                label = { Text(Format.mode(mode)) },
                                leadingIcon = { Icon(mode.icon(), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                            )
                        }
                    }
                    Text(stringResource(R.string.planner_walking_speed), style = MaterialTheme.typography.labelLarge)
                    ConnectedToggleGroup(
                        options = WalkingSpeed.entries.toList(), selected = state.walkingSpeed, onSelect = viewModel::setWalkingSpeed,
                        label = { stringResource(when (it) { WalkingSpeed.SLOW -> R.string.walking_slow; WalkingSpeed.NORMAL -> R.string.walking_normal; WalkingSpeed.FAST -> R.string.walking_fast }) },
                        modifier = Modifier.fillMaxWidth(),
                        // The card is surfaceContainer, the toggles' default: lift them one tone.
                        uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.AccessibleForward, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.planner_wheelchair), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.planner_wheelchair_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.wheelchair, onCheckedChange = viewModel::setWheelchair)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.PedalBike, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.planner_bike), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.planner_bike_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.withBike, onCheckedChange = viewModel::setWithBike)
                    }
                }
            }
        }
    }

    if (pickDate) {
        val initial = (state.time ?: ZonedDateTime.now(TransitZone)).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        org.southtyrol.transit.feature.common.TransitDatePickerDialog(
            initial, stringResource(R.string.action_next),
            onConfirm = { chosenDate = it; pickDate = false; pickTime = true }, onDismiss = { pickDate = false },
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

/** Home, Work and the user's own places as cards: a tap plans there, a long press offers starting from there. */
@Composable
private fun YourPlaces(places: List<SavedItem>, onUse: (Place, Boolean) -> Unit, onAdd: () -> Unit, onEdit: () -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.planner_saved_places)) {
            if (places.isNotEmpty()) TextButton(onClick = onEdit) { Text(stringResource(R.string.places_edit)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(places, key = { it.id }) { item ->
                val place = item.place ?: return@items
                PlaceCard(item, place, onClick = { onUse(place, false) }, onStartHere = { onUse(place, true) })
            }
            item(key = "add") { AddPlaceCard(onAdd) }
        }
    }
}

private val PlaceCardWidth = 120.dp

@Composable
private fun PlaceCard(item: SavedItem, place: Place, onClick: () -> Unit, onStartHere: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val label = savedPlaceLabel(item)
    Box {
        Column(
            Modifier.width(PlaceCardWidth)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.place_start_here))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaceGlyph(item)
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(place.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_start_here)) },
                leadingIcon = { Icon(Icons.Rounded.TripOrigin, contentDescription = null) },
                onClick = { menu = false; onStartHere() },
            )
        }
    }
}

@Composable
private fun AddPlaceCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.width(PlaceCardWidth),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp)) }
            }
            Text(stringResource(R.string.place_add), style = MaterialTheme.typography.titleMedium, maxLines = 2)
        }
    }
}
