package org.southtyrol.transit.feature.schedules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.feature.common.groupGap
import org.southtyrol.transit.feature.common.groupedItemColors
import org.southtyrol.transit.feature.planner.displayName
import org.southtyrol.transit.model.TripSchedule
import org.southtyrol.transit.ui.Navigator
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import javax.inject.Inject

@HiltViewModel
class TripSchedulesViewModel @Inject constructor(private val saved: SavedRepository) : ViewModel() {
    /** In creation order, oldest first; null while loading. */
    val schedules: StateFlow<List<TripSchedule>?> = saved.saved
        .map { items -> items.filter { it.kind == SavedKind.TRIP_SCHEDULE }.mapNotNull { it.tripSchedule } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setEnabled(schedule: TripSchedule, enabled: Boolean) = viewModelScope.launch { saved.saveSchedule(schedule.copy(enabled = enabled)) }
    fun remove(schedule: TripSchedule) = viewModelScope.launch { saved.remove(SavedKind.TRIP_SCHEDULE, schedule.id) }
}

@Composable
fun TripSchedulesScreen(navigator: Navigator, viewModel: TripSchedulesViewModel = hiltViewModel()) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { navigator.editTripSchedule() }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.schedules_add))
                    }
                },
            )
        },
    ) { padding ->
        val items = schedules ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            if (items.isEmpty()) item {
                MessageState(
                    stringResource(R.string.schedules_empty), stringResource(R.string.schedules_empty_hint), icon = Icons.Rounded.EventRepeat,
                    action = stringResource(R.string.schedules_add), onAction = { navigator.editTripSchedule() },
                )
            }
            itemsIndexed(items, key = { _, s -> s.id }) { index, schedule ->
                ScheduleRow(
                    schedule, index, items.size,
                    onClick = { navigator.tripSchedule(schedule.id) },
                    onEnabled = { viewModel.setEnabled(schedule, it) },
                    onEdit = { navigator.editTripSchedule(schedule.id) },
                    onDelete = { viewModel.remove(schedule) },
                )
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: TripSchedule, index: Int, count: Int, onClick: () -> Unit, onEnabled: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // A disabled schedule stays listed, its texts muted.
    val textAlpha = if (schedule.enabled) 1f else 0.5f
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count), colors = groupedItemColors(), modifier = Modifier.groupGap(index, count),
        supportingContent = {
            Column(Modifier.alpha(textAlpha)) {
                Text("${displayName(schedule.from)} → ${displayName(schedule.to)}")
                Text(shortDays(schedule.days))
                Text(windowLabel(schedule))
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = schedule.enabled, onCheckedChange = onEnabled, modifier = Modifier.padding(end = 4.dp))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.schedule_options, schedule.name)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.schedule_edit)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.schedule_delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
    ) { Text(schedule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(textAlpha)) }
}

/** The chosen days, Monday first, as short weekdays in the app's language: "Mon, Tue, Wed". */
@Composable
internal fun shortDays(days: Set<DayOfWeek>): String {
    val locale = Format.locale()
    return days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

/** A schedule's clock time as the app shows times elsewhere. */
@Composable
internal fun clockTime(time: LocalTime): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Format.locale()).format(time)

/** "Leave 07:15–08:00" or "Arrive 07:15–08:00". */
@Composable
internal fun windowLabel(schedule: TripSchedule): String =
    stringResource(if (schedule.arriveBy) R.string.schedule_arrive_window else R.string.schedule_leave_window, clockTime(schedule.start), clockTime(schedule.end))
