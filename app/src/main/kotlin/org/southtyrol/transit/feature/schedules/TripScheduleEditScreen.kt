package org.southtyrol.transit.feature.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlexBox
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.southtyrol.transit.R
import org.southtyrol.transit.data.Codec
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.ConnectedToggleGroup
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.feature.common.PlaceSearchSheet
import org.southtyrol.transit.feature.common.groupGap
import org.southtyrol.transit.feature.common.groupedItemColors
import org.southtyrol.transit.feature.planner.displayName
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.TripSchedule
import org.southtyrol.transit.ui.Navigator
import org.southtyrol.transit.ui.TripScheduleEditRoute
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

/** A schedule being edited: From and To may still be missing. */
data class ScheduleDraft(
    val name: String = "",
    val from: Place? = null,
    val to: Place? = null,
    val days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val start: LocalTime = LocalTime.of(7, 0),
    val end: LocalTime = LocalTime.of(8, 0),
    val arriveBy: Boolean = false,
    val enabled: Boolean = true,
) {
    val problems: Set<TripSchedule.Problem> get() = TripSchedule.problems(name, from, to, days, start, end)

    /** The schedule to save under [id], or null while a place is missing or a rule fails. */
    fun schedule(id: String): TripSchedule? {
        if (from == null || to == null || problems.isNotEmpty()) return null
        return TripSchedule(id, name.trim(), from, to, days, start, end, arriveBy, enabled)
    }

    companion object {
        fun of(s: TripSchedule) = ScheduleDraft(s.name, s.from, s.to, s.days, s.start, s.end, s.arriveBy, s.enabled)

        private val json = Json { ignoreUnknownKeys = true }

        /** For the saved state handle, so the draft survives rotation and process death. */
        fun encode(d: ScheduleDraft): String = buildJsonObject {
            put("name", d.name)
            d.from?.let { put("from", Codec.place(it)) }; d.to?.let { put("to", Codec.place(it)) }
            put("days", JsonArray(d.days.sorted().map { JsonPrimitive(it.value) }))
            put("start", d.start.toSecondOfDay()); put("end", d.end.toSecondOfDay())
            put("arriveBy", d.arriveBy); put("enabled", d.enabled)
        }.toString()

        fun decode(text: String): ScheduleDraft? = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            ScheduleDraft(
                o.getValue("name").jsonPrimitive.content,
                o["from"]?.jsonPrimitive?.content?.let(Codec::place), o["to"]?.jsonPrimitive?.content?.let(Codec::place),
                o.getValue("days").jsonArray.map { DayOfWeek.of(it.jsonPrimitive.int) }.toSet(),
                LocalTime.ofSecondOfDay(o.getValue("start").jsonPrimitive.int.toLong()), LocalTime.ofSecondOfDay(o.getValue("end").jsonPrimitive.int.toLong()),
                o.getValue("arriveBy").jsonPrimitive.boolean, o.getValue("enabled").jsonPrimitive.boolean,
            )
        }.getOrNull()
    }
}

@HiltViewModel
class TripScheduleEditViewModel @Inject constructor(private val handle: SavedStateHandle, private val saved: SavedRepository) : ViewModel() {
    /** Empty for a new schedule. */
    val id: String = handle.toRoute<TripScheduleEditRoute>().id
    private val _draft = MutableStateFlow(handle.get<String>(KEY)?.let(ScheduleDraft::decode))
    /** Null while an existing schedule loads. */
    val draft: StateFlow<ScheduleDraft?> = _draft.asStateFlow()
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()
    private var saving = false

    init {
        if (_draft.value == null) {
            if (id.isBlank()) set(ScheduleDraft())
            else viewModelScope.launch {
                val schedule = saved.saved.first().firstOrNull { it.kind == SavedKind.TRIP_SCHEDULE && it.id == id }?.tripSchedule
                if (schedule == null) _missing.value = true else set(ScheduleDraft.of(schedule))
            }
        }
    }

    fun update(change: (ScheduleDraft) -> ScheduleDraft) { _draft.value?.let { set(change(it)) } }

    private fun set(draft: ScheduleDraft) {
        _draft.value = draft
        handle[KEY] = ScheduleDraft.encode(draft)
    }

    /** Saves the draft, then calls [onSaved]; does nothing while a rule fails. */
    fun save(onSaved: () -> Unit) {
        val schedule = _draft.value?.schedule(id) ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            saved.saveSchedule(schedule)
            onSaved()
        }
    }

    private companion object { const val KEY = "draft" }
}

private enum class Picking { FROM, TO, START, END }

@Composable
fun TripScheduleEditScreen(navigator: Navigator, viewModel: TripScheduleEditViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.id.isBlank()) R.string.schedule_new_title else R.string.schedule_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(navigator::back) }, enabled = draft?.schedule(viewModel.id) != null) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        val d = draft
        when {
            missing -> MessageState(
                stringResource(R.string.schedule_missing), icon = Icons.Rounded.Schedule, modifier = Modifier.padding(padding),
                action = stringResource(R.string.action_back), onAction = navigator::back,
            )
            d == null -> TransitLoading(contained = true, modifier = Modifier.padding(padding))
            else -> Editor(d, viewModel::update, onPick = { picking = it }, modifier = Modifier.padding(padding))
        }
    }

    val d = draft ?: return
    when (val p = picking) {
        Picking.FROM, Picking.TO -> PlaceSearchSheet(
            hint = stringResource(if (p == Picking.FROM) R.string.search_from_hint else R.string.search_to_hint),
            onChoose = { place -> viewModel.update { if (p == Picking.FROM) it.copy(from = place) else it.copy(to = place) }; picking = null },
            onDismiss = { picking = null },
        )
        Picking.START, Picking.END -> {
            val initial = if (p == Picking.START) d.start else d.end
            val state = rememberTimePickerState(initial.hour, initial.minute)
            TimePickerDialog(
                onDismissRequest = { picking = null },
                title = { Text(stringResource(if (p == Picking.START) R.string.schedule_start else R.string.schedule_end)) },
                confirmButton = {
                    TextButton(onClick = {
                        val time = LocalTime.of(state.hour, state.minute)
                        viewModel.update { if (p == Picking.START) it.copy(start = time) else it.copy(end = time) }
                        picking = null
                    }) { Text(stringResource(R.string.action_done)) }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) } },
            ) { TimePicker(state) }
        }
        null -> Unit
    }
}

@Composable
private fun Editor(d: ScheduleDraft, update: ((ScheduleDraft) -> ScheduleDraft) -> Unit, onPick: (Picking) -> Unit, modifier: Modifier) {
    val problems = d.problems
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = d.name, onValueChange = { name -> update { it.copy(name = name) } },
            label = { Text(stringResource(R.string.schedule_name)) }, singleLine = true,
            isError = TripSchedule.Problem.NAME_BLANK in problems,
            supportingText = if (TripSchedule.Problem.NAME_BLANK in problems) { { Text(stringResource(R.string.schedule_name_missing)) } } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            PlaceRow(stringResource(R.string.planner_from), d.from, Icons.Rounded.TripOrigin, 0) { onPick(Picking.FROM) }
            PlaceRow(stringResource(R.string.planner_to), d.to, Icons.Rounded.LocationOn, 1) { onPick(Picking.TO) }
            if (TripSchedule.Problem.SAME_PLACE in problems) ProblemText(R.string.schedule_same_place)
        }
        Column {
            Text(stringResource(R.string.schedule_days), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            FlexBox(config = { wrap(FlexWrap.Wrap); columnGap(8.dp) }) {
                DayOfWeek.entries.forEach { day ->
                    val chosen = day in d.days
                    FilterChip(
                        selected = chosen,
                        onClick = { update { it.copy(days = if (chosen) it.days - day else it.days + day) } },
                        label = { Text(shortDays(setOf(day))) },
                    )
                }
            }
            if (TripSchedule.Problem.NO_DAYS in problems) ProblemText(R.string.schedule_no_days)
        }
        ConnectedToggleGroup(
            options = listOf(false, true), selected = d.arriveBy, onSelect = { arrive -> update { it.copy(arriveBy = arrive) } },
            label = { stringResource(if (it) R.string.schedule_arrive_between else R.string.schedule_leave_between) },
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField(stringResource(R.string.schedule_start), d.start, Modifier.weight(1f)) { onPick(Picking.START) }
                TimeField(stringResource(R.string.schedule_end), d.end, Modifier.weight(1f)) { onPick(Picking.END) }
            }
            if (TripSchedule.Problem.START_NOT_BEFORE_END in problems) ProblemText(R.string.schedule_times)
        }
    }
}

@Composable
private fun PlaceRow(label: String, place: Place?, icon: ImageVector, index: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, 2), colors = groupedItemColors(), modifier = Modifier.groupGap(index, 2),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(label) },
    ) {
        Text(
            place?.let { displayName(it) } ?: stringResource(R.string.planner_choose),
            color = if (place == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TimeField(label: String, time: LocalTime, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text("$label  ${clockTime(time)}")
    }
}

@Composable
private fun ProblemText(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
}
