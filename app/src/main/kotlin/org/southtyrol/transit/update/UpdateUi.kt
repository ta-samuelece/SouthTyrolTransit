package org.southtyrol.transit.update

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.UserSettings
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: UpdateManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    val enabled = updates.enabled
    val currentVersion = updates.currentVersion
    val state: StateFlow<UpdateState> = updates.state
    val prefs: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    private val dismissed = MutableStateFlow(updates.dismissed)

    /** Whether the app-wide prompt should be visible (manual checks from Settings always show it). */
    val prompt: StateFlow<Boolean> = combine(state, prefs, dismissed) { s, p, d ->
        when (s) {
            is UpdateState.Available -> !d && s.release.version != p.skippedUpdate
            is UpdateState.Downloading, is UpdateState.Ready -> true
            is UpdateState.Failed -> s.release != null
            else -> false
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Called once per app start; respects the "check on start" setting. */
    fun checkOnStart() = viewModelScope.launch {
        if (settings.current().autoUpdateCheck) updates.check()
    }

    fun checkNow() = viewModelScope.launch {
        updates.dismissed = false
        dismissed.value = false
        settings.setSkippedUpdate("")
        updates.check(force = true)
    }

    fun update(release: AppRelease) = updates.startDownload(release)
    fun cancel(release: AppRelease) = updates.cancelDownload(release)
    fun later() { updates.dismissed = true; dismissed.value = true }
    fun skip(release: AppRelease) = viewModelScope.launch { settings.setSkippedUpdate(release.version); later() }
    fun setAuto(value: Boolean) = viewModelScope.launch { settings.setAutoUpdateCheck(value) }

    fun canInstall() = updates.canInstall()
    fun permissionIntent() = updates.permissionIntent()
    fun installIntent(file: File) = updates.installIntent(file)
}

/** Starts the system installer, first sending the user to "install unknown apps" if needed. */
@Composable
private fun rememberInstaller(viewModel: UpdateViewModel): (File) -> Unit {
    val context = LocalContext.current
    val pending = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<File?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pending.value
        if (file != null && viewModel.canInstall()) runCatching { context.startActivity(viewModel.installIntent(file)) }
    }
    return { file ->
        if (viewModel.canInstall()) {
            try { context.startActivity(viewModel.installIntent(file)) } catch (_: ActivityNotFoundException) {}
        } else {
            pending.value = file
            permission.launch(viewModel.permissionIntent())
        }
    }
}

/** App-wide prompt: offered on start when a newer GitHub release exists, then shows download progress. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatePrompt(viewModel: UpdateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.checkOnStart() }
    val show by viewModel.prompt.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!show) return
    val install = rememberInstaller(viewModel)
    val uri = LocalUriHandler.current
    val s = state

    // Hand the verified file to Android's installer as soon as it is ready (the user still confirms).
    LaunchedEffect(s) { if (s is UpdateState.Ready) install(s.file) }

    when (s) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = viewModel::later,
            icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.update_available_body, s.release.version, viewModel.currentVersion))
                    if (s.release.notes.isNotBlank()) {
                        Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall)
                        org.southtyrol.transit.feature.common.MarkdownText(
                            s.release.notes,
                            modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    TextButton(onClick = { viewModel.skip(s.release) }) { Text(stringResource(R.string.update_skip)) }
                }
            },
            confirmButton = { Button(onClick = { viewModel.update(s.release) }) { Text(stringResource(R.string.update_now)) } },
            dismissButton = { TextButton(onClick = viewModel::later) { Text(stringResource(R.string.update_later)) } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
            title = { Text(stringResource(R.string.update_downloading)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.total > 0) {
                        LinearWavyProgressIndicator(progress = { (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${s.bytes / 1_048_576} / ${s.total / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
                    } else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.cancel(s.release) }) { Text(stringResource(R.string.action_cancel)) } },
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = viewModel::later,
            icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
            title = { Text(stringResource(R.string.update_ready_title, s.release.version)) },
            text = { Text(stringResource(R.string.update_ready)) },
            confirmButton = { Button(onClick = { install(s.file) }) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(onClick = viewModel::later) { Text(stringResource(R.string.update_later)) } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = viewModel::later,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(stringResource(errorText(s.error))) },
            confirmButton = {
                val release = s.release
                if (release != null && s.error == UpdateError.DOWNLOAD) Button(onClick = { viewModel.update(release) }) { Text(stringResource(R.string.update_retry)) }
                else if (release != null) Button(onClick = { uri.openUri(release.pageUrl); viewModel.later() }) { Text(stringResource(R.string.update_open_page)) }
            },
            dismissButton = { TextButton(onClick = viewModel::later) { Text(stringResource(R.string.update_later)) } },
        )
        else -> Unit
    }
}

private fun errorText(error: UpdateError) = when (error) {
    UpdateError.NETWORK -> R.string.update_failed_network
    UpdateError.NO_APK -> R.string.update_failed_no_apk
    UpdateError.DOWNLOAD -> R.string.update_failed_download
    UpdateError.WRONG_PACKAGE -> R.string.update_failed_package
    UpdateError.NOT_NEWER -> R.string.update_failed_not_newer
    UpdateError.SIGNATURE -> R.string.update_failed_signature
}

/** Settings section: installed version, manual check and the "check on start" switch. */
@Composable
fun UpdateSettings(switchRow: @Composable (label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    Text(stringResource(R.string.settings_version, viewModel.currentVersion), style = MaterialTheme.typography.bodyLarge)
    if (!viewModel.enabled) {
        Text(stringResource(R.string.update_disabled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    when (val s = state) {
        UpdateState.Checking -> Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
        is UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodyMedium)
        is UpdateState.Failed -> if (s.release == null) Text(stringResource(errorText(s.error)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
    val available = (state as? UpdateState.Available)?.release
    if (available != null) Button(onClick = { viewModel.update(available) }) { Text(stringResource(R.string.update_to, available.version)) }
    else OutlinedButton(onClick = viewModel::checkNow, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
        Text(stringResource(R.string.update_check_now))
    }
    switchRow(stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_hint), prefs.autoUpdateCheck) { viewModel.setAuto(it) }
}
