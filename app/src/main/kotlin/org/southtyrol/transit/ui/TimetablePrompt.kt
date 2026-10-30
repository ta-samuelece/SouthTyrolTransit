package org.southtyrol.transit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.ScheduleRepository
import org.southtyrol.transit.data.ScheduleStatus
import org.southtyrol.transit.data.TimetableNeed
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.model.GtfsTime
import org.southtyrol.transit.work.BackgroundWork
import javax.inject.Inject
import javax.inject.Singleton

/** Set once the user answered the timetable prompt; it is not shown again until the next app start. */
@Singleton
class TimetablePromptSession @Inject constructor() {
    var dismissed = false
}

@HiltViewModel
class TimetablePromptViewModel @Inject constructor(
    private val timetable: ScheduleRepository,
    private val work: BackgroundWork,
    private val session: TimetablePromptSession,
) : ViewModel() {
    private val need = MutableStateFlow<TimetableNeed?>(null)
    val info = timetable.info

    /** What to prompt for: nothing while a download is already running or once the user answered. */
    val prompt: StateFlow<TimetableNeed?> = need.asStateFlow()

    init {
        // A download started elsewhere (Settings, the weekly sync) answers the prompt; so does a check
        // that found the timetable current.
        viewModelScope.launch {
            timetable.status.collect { s ->
                if (s is ScheduleStatus.Downloading || s is ScheduleStatus.Importing || s is ScheduleStatus.UpToDate) need.value = null
            }
        }
    }

    /** Called when the app opens: checks the installed timetable, and the server for a newer one. */
    fun check() = viewModelScope.launch {
        if (session.dismissed) return@launch
        val found = runCatching { timetable.need() }.getOrNull()
        val status = timetable.status.value
        if (status == ScheduleStatus.Idle || status is ScheduleStatus.Failed) need.value = found
    }

    fun download() { work.downloadTimetableNow(); later() }
    fun later() { session.dismissed = true; need.value = null }
}

/**
 * App-wide prompt shown on start when the offline timetable is missing, unreadable, expired or older
 * than the one on the server. Waits while [blocked] (another start-up dialog is showing).
 */
@Composable
fun TimetablePrompt(blocked: Boolean, viewModel: TimetablePromptViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.check() }
    val need by viewModel.prompt.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val n = need ?: return
    if (blocked) return
    AlertDialog(
        onDismissRequest = viewModel::later,
        icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
        title = {
            Text(stringResource(when (n) {
                TimetableNeed.MISSING -> R.string.timetable_prompt_missing_title
                TimetableNeed.EXPIRED -> R.string.timetable_prompt_expired_title
                TimetableNeed.UPDATE_AVAILABLE -> R.string.timetable_prompt_update_title
            }))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val lastDate = info?.lastDate ?: 0
                Text(when (n) {
                    TimetableNeed.MISSING -> stringResource(R.string.timetable_prompt_missing_body)
                    TimetableNeed.EXPIRED ->
                        if (lastDate > 0) stringResource(R.string.timetable_prompt_expired_body, Format.date(GtfsTime.fromDateKey(lastDate)))
                        else stringResource(R.string.timetable_prompt_update_body)
                    TimetableNeed.UPDATE_AVAILABLE -> stringResource(R.string.timetable_prompt_update_body)
                })
                Text(stringResource(R.string.timetable_prompt_size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = viewModel::download) { Text(stringResource(R.string.timetable_download)) } },
        dismissButton = { TextButton(onClick = viewModel::later) { Text(stringResource(R.string.update_later)) } },
    )
}
