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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibleForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Signpost
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.southtyrol.transit.R
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.data.ScheduleStatus
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.ConnectedToggleGroup
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.icon
import org.southtyrol.transit.feature.common.TopLevelBar
import org.southtyrol.transit.feature.common.rememberLocationPermission
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
    val search by viewModel.search.collectAsStateWithLifecycle()
    val recentJourneys by viewModel.recentJourneys.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val savedJourneys by viewModel.savedJourneys.collectAsStateWithLifecycle()
    val scheduleInfo by viewModel.scheduleInfo.collectAsStateWithLifecycle()
    val scheduleStatus by viewModel.scheduleStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingLocationField by rememberSaveable { mutableStateOf<Field?>(null) }
    val requestLocation = rememberLocationPermission { granted ->
        val field = pendingLocationField ?: return@rememberLocationPermission
        if (granted) viewModel.useCurrentLocation(field) else viewModel.locationDenied()
    }
    val useLocation: (Field) -> Unit = { field -> pendingLocationField = field; requestLocation() }

    val deniedText = stringResource(R.string.location_denied)
    val unavailableText = stringResource(R.string.location_unavailable)
    LaunchedEffect(state.locationError) {
        val error = state.locationError ?: return@LaunchedEffect
        snackbar.showSnackbar(if (error is LocationResult.PermissionDenied) deniedText else unavailableText)
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
                    onAction = { navigator.select(org.southtyrol.transit.ui.TopLevel.SETTINGS) },
                )
            }
            val home = savedPlaces.firstOrNull { it.id == SavedRepository.HOME }?.place
            val work = savedPlaces.firstOrNull { it.id == SavedRepository.WORK }?.place
            if (home != null || work != null) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    home?.let { p -> QuickChip(stringResource(R.string.place_home), Icons.Rounded.Home) { viewModel.useRoute(state.from ?: p, p) } }
                    work?.let { p -> QuickChip(stringResource(R.string.place_work), Icons.Rounded.Work) { viewModel.useRoute(state.from ?: p, p) } }
                }
            }
            if (savedJourneys.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.planner_saved_journeys)) }
                itemsIndexed(savedJourneys, key = { _, it -> "saved:" + it.id }) { index, item ->
                    val route = item.route ?: return@itemsIndexed
                    RouteItem(item.label, Icons.Rounded.Bookmark, index, savedJourneys.size) { viewModel.useRoute(route.first, route.second) }
                }
            }
            if (recentJourneys.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.planner_recent)) }
                itemsIndexed(recentJourneys, key = { _, it -> "recent:" + it.id }) { index, item ->
                    val route = item.route ?: return@itemsIndexed
                    RouteItem(item.label, Icons.Rounded.History, index, recentJourneys.size) { viewModel.useRoute(route.first, route.second) }
                }
            }
            item {
                OutlinedCard(onClick = navigator::tickets, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.tickets_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.tickets_teaser), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    val pointName = stringResource(R.string.point_on_map)
    if (search.field != null) {
        PlaceSearchSheet(
            state = search,
            recents = viewModel.recentPlaces.collectAsStateWithLifecycle().value.mapNotNull { it.place },
            saved = savedPlaces.mapNotNull { item -> item.place?.let { item.label to it } },
            onQuery = viewModel::query,
            onChoose = viewModel::choose,
            onUseLocation = { useLocation(search.field ?: Field.FROM) },
            onStop = viewModel::chooseStop,
            onPoint = { viewModel.choosePoint(it, pointName) },
            onSaveAs = { id, label, place -> viewModel.saveHomeOrWork(id, label, place) },
            onDismiss = viewModel::closeSearch,
        )
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.planner_preference), style = MaterialTheme.typography.labelLarge)
                ConnectedToggleGroup(
                    options = RoutePreference.entries.toList(), selected = state.preference, onSelect = viewModel::setPreference,
                    label = { stringResource(preferenceLabel(it)) }, modifier = Modifier.fillMaxWidth(),
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
            }
        }
    }

    if (pickDate) {
        val initial = (state.time ?: ZonedDateTime.now(TransitZone)).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { chosenDate = dateState.selectedDateMillis ?: initial; pickDate = false; pickTime = true }) { Text(stringResource(R.string.action_next)) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(dateState) }
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
private fun QuickChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) })
}

@Composable
private fun RouteItem(label: String, icon: ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(icon, contentDescription = null) },
    ) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/** Contextual bottom sheet for choosing a stop, address, POI or the current location. */
@Composable
private fun PlaceSearchSheet(
    state: PlaceSearchState,
    recents: List<Place>,
    saved: List<Pair<String, Place>>,
    onQuery: (String) -> Unit,
    onChoose: (Place) -> Unit,
    onUseLocation: () -> Unit,
    onStop: (org.southtyrol.transit.model.Stop) -> Unit,
    onPoint: (org.southtyrol.transit.model.Point) -> Unit,
    onSaveAs: (String, String, Place) -> Unit,
    onDismiss: () -> Unit,
) {
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    if (mapOpen) {
        org.southtyrol.transit.feature.common.MapPickerDialog(
            allowPoint = true,
            onStop = { mapOpen = false; onStop(it) },
            onPoint = { mapOpen = false; onPoint(it) },
            onDismiss = { mapOpen = false },
        )
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val homeLabel = stringResource(R.string.place_home)
    val workLabel = stringResource(R.string.place_work)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(stringResource(if (state.field == Field.FROM) R.string.search_from_hint else R.string.search_to_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.results.firstOrNull()?.let(onChoose) }),
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxHeight().imePadding()) {
            if (state.query.isBlank()) {
                item {
                    SheetItem(stringResource(R.string.location_use_current), null, Icons.Rounded.MyLocation, 0, 2) { onUseLocation() }
                }
                item {
                    SheetItem(stringResource(R.string.pick_on_map), null, Icons.Rounded.Map, 1, 2) { mapOpen = true }
                }
                if (saved.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.planner_saved_places)) }
                    itemsIndexed(saved) { index, (label, place) -> SheetItem(label, place.name, Icons.Rounded.Bookmark, index, saved.size) { onChoose(place) } }
                }
                if (recents.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.planner_recent_places)) }
                    itemsIndexed(recents) { index, place -> SheetItem(place.name, place.locality.takeIf { it.isNotBlank() && !place.name.contains(it) }, Icons.Rounded.History, index, recents.size) { onChoose(place) } }
                }
            } else {
                if (state.loading) item { TransitLoading() }
                state.error?.let { error ->
                    item { StatusBanner(Format.error(error) + if (state.results.isNotEmpty()) "\n" + stringResource(R.string.search_offline_results) else "", kind = BannerKind.WARNING) }
                }
                if (!state.loading && state.results.isEmpty() && state.error == null && state.query.length >= 2) {
                    item { Text(stringResource(R.string.search_no_results), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                itemsIndexed(state.results, key = { _, p -> p.id + p.name }) { index, place ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        SegmentedListItem(
                            onClick = { onChoose(place) },
                            onLongClick = { menu = true },
                            onLongClickLabel = stringResource(R.string.place_save_as),
                            shapes = ListItemDefaults.segmentedShapes(index, state.results.size),
                            leadingContent = { Icon(placeIcon(place.type), contentDescription = placeTypeLabel(place.type)) },
                            supportingContent = place.locality.takeIf { it.isNotBlank() && !place.name.contains(it) }?.let { { Text(it) } },
                        ) { Text(place.name) }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.place_save_home)) }, onClick = { onSaveAs(SavedRepository.HOME, homeLabel, place); menu = false })
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.place_save_work)) }, onClick = { onSaveAs(SavedRepository.WORK, workLabel, place); menu = false })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetItem(title: String, subtitle: String?, icon: ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    ) { Text(title) }
}

fun placeIcon(type: PlaceType): ImageVector = when (type) {
    PlaceType.STOP -> TransportMode.BUS.icon()
    PlaceType.POI -> Icons.Rounded.Place
    PlaceType.ADDRESS, PlaceType.STREET -> Icons.Rounded.Signpost
    PlaceType.COORDINATE -> Icons.Rounded.MyLocation
    else -> Icons.Rounded.LocationOn
}

@Composable
fun placeTypeLabel(type: PlaceType): String = stringResource(
    when (type) {
        PlaceType.STOP -> R.string.place_type_stop
        PlaceType.POI -> R.string.place_type_poi
        PlaceType.ADDRESS, PlaceType.STREET -> R.string.place_type_address
        PlaceType.LOCALITY -> R.string.place_type_locality
        else -> R.string.place_type_place
    },
)
