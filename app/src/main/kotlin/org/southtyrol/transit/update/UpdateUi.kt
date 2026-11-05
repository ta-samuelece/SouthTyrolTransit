package org.southtyrol.transit.update

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLocale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.UserSettings
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.whatsnew.WhatsNewAllChanges
import org.southtyrol.transit.whatsnew.WhatsNewBody
import org.southtyrol.transit.whatsnew.WhatsNewFooter
import org.southtyrol.transit.whatsnew.WhatsNewHero
import java.io.File
import java.time.format.DateTimeFormatter
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

    /** Whether the app-wide sheet should be visible. */
    val prompt: StateFlow<Boolean> = combine(state, prefs, updates.dismissed) { s, p, d -> shouldPrompt(s, d, p.skippedUpdate) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Called once per app start; respects the "check on start" setting. */
    fun checkOnStart() = viewModelScope.launch {
        val prefs = settings.current()
        if (prefs.autoUpdateCheck) updates.check(preview = prefs.updateChannel == org.southtyrol.transit.data.UpdateChannel.PREVIEW)
    }

    fun checkNow() = viewModelScope.launch {
        updates.setDismissed(false)
        settings.setSkippedUpdate("")
        updates.check(force = true, preview = settings.current().updateChannel == org.southtyrol.transit.data.UpdateChannel.PREVIEW)
    }

    fun update(release: AppRelease) = updates.startDownload(release)
    fun cancel(release: AppRelease) = updates.cancelDownload(release)
    fun later() = updates.setDismissed(true)

    /** Settings' button: brings the sheet back after "Later" or "Skip this version". */
    fun reopen() = viewModelScope.launch {
        settings.setSkippedUpdate("")
        updates.setDismissed(false)
    }

    fun skip(release: AppRelease) = viewModelScope.launch { settings.setSkippedUpdate(release.version); later() }
    fun setAuto(value: Boolean) = viewModelScope.launch { settings.setAutoUpdateCheck(value) }

    /** Switching channel checks right away, so the user immediately sees what that channel offers. */
    fun setChannel(value: org.southtyrol.transit.data.UpdateChannel) = viewModelScope.launch {
        settings.setUpdateChannel(value)
        settings.setSkippedUpdate("")
        updates.setDismissed(false)
        updates.check(force = true, preview = value == org.southtyrol.transit.data.UpdateChannel.PREVIEW)
    }

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

/**
 * App-wide sheet: offers a newer GitHub release on start, then follows it through download and install.
 * It cannot be swiped away during a download, so Android's installer never opens unannounced.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatePrompt(viewModel: UpdateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.checkOnStart() }
    val show by viewModel.prompt.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!show) return
    val s = state
    val release = s.release ?: return
    val install = rememberInstaller(viewModel)

    // Hand the verified file to Android's installer as soon as it is ready (the user still confirms).
    LaunchedEffect(s) { if (s is UpdateState.Ready) install(s.file) }

    val locked = s is UpdateState.Downloading
    val currentLocked by rememberUpdatedState(locked)
    val confirm = remember { { value: SheetValue -> value != SheetValue.Hidden || !currentLocked } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> Unit = { action -> scope.launch { sheetState.hide() }.invokeOnCompletion { action() } }
    val locale = LocalLocale.current.platformLocale
    val language = locale.language
    val content = remember(release, language) { release.whatsNew?.inLanguage(language) }
    val highlights = content?.highlights.orEmpty()
    val notes = content?.notes?.takeIf { it.isNotBlank() } ?: release.notes
    var showAll by rememberSaveable(release.version) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val published = release.published?.let { it.atZone(TransitZone).format(DateTimeFormatter.ofPattern("d MMM", locale)) }
    val youHave = published?.let { stringResource(R.string.update_you_have_published, viewModel.currentVersion, it) }
        ?: stringResource(R.string.update_you_have, viewModel.currentVersion)

    // The buttons of the current state; the first screen adds "Skip this version", the full notes the release page.
    val actions: @Composable RowScope.() -> Unit = {
        when (s) {
            is UpdateState.Downloading -> {
                TextButton(onClick = { viewModel.cancel(release) }) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = {}, enabled = false) { Text(stringResource(R.string.update_install)) }
            }
            is UpdateState.Ready -> {
                TextButton(onClick = { closeThen(viewModel::later) }) { Text(stringResource(R.string.update_later)) }
                Button(onClick = { install(s.file) }) { Text(stringResource(R.string.update_install)) }
            }
            is UpdateState.Failed -> {
                TextButton(onClick = { closeThen(viewModel::later) }) { Text(stringResource(R.string.update_later)) }
                if (s.error == UpdateError.DOWNLOAD) {
                    Button(onClick = { viewModel.update(release) }) { Text(stringResource(R.string.update_retry)) }
                } else {
                    Button(onClick = { uri.openUri(release.pageUrl); closeThen(viewModel::later) }) { Text(stringResource(R.string.update_open_page)) }
                }
            }
            else -> {
                TextButton(onClick = { closeThen(viewModel::later) }) { Text(stringResource(R.string.update_later)) }
                Button(onClick = { viewModel.update(release) }) { Text(stringResource(R.string.update_now)) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!currentLocked) viewModel.later() },
        sheetState = sheetState,
        sheetGesturesEnabled = !locked,
        dragHandle = if (locked) null else ({ BottomSheetDefaults.DragHandle() }),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !locked),
    ) {
        if (showAll) {
            WhatsNewAllChanges(stringResource(R.string.update_whats_new_in, release.version), youHave, notes, onBack = { showAll = false }) {
                TextButton(onClick = { uri.openUri(release.pageUrl) }) { Text(stringResource(R.string.update_release_page)) }
                Spacer(Modifier.weight(1f))
                actions()
            }
        } else {
            if (locked) Spacer(Modifier.height(16.dp))
            UpdateHero(s, release, youHave)
            WhatsNewBody(highlights, notes, stringResource(R.string.update_whats_new), onAllChanges = { showAll = true })
            WhatsNewFooter {
                // Takes the room that is left, so a long translation wraps instead of pushing "Update" away.
                Box(Modifier.weight(1f)) {
                    if (s is UpdateState.Available) {
                        TextButton(onClick = { closeThen { viewModel.skip(release) } }) {
                            Text(stringResource(R.string.update_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                actions()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateHero(state: UpdateState, release: AppRelease, youHave: String) {
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    when (state) {
        is UpdateState.Downloading -> WhatsNewHero(
            Icons.Rounded.SystemUpdate, stringResource(R.string.update_downloading), release.version,
            trailing = { if (state.total > 0) Text("${state.bytes / 1_048_576} / ${state.total / 1_048_576} MB", style = MaterialTheme.typography.titleSmall, color = onContainer) },
        ) {
            if (state.total > 0) {
                LinearWavyProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            } else {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            Text(stringResource(R.string.update_keep_reading), style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
        is UpdateState.Ready -> WhatsNewHero(Icons.Rounded.DownloadDone, stringResource(R.string.update_ready_hero), release.version) {
            Text(stringResource(R.string.update_ready), style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
        is UpdateState.Failed -> {
            val onError = MaterialTheme.colorScheme.onErrorContainer
            WhatsNewHero(
                Icons.Rounded.Warning, stringResource(R.string.update_failed_title), release.version,
                container = MaterialTheme.colorScheme.errorContainer, content = onError,
            ) {
                Text(stringResource(errorText(state.error)), style = MaterialTheme.typography.bodyMedium, color = onError)
            }
        }
        else -> WhatsNewHero(
            Icons.Rounded.SystemUpdate, stringResource(R.string.update_available_title), release.version,
            trailing = {
                if (release.preview) {
                    Text(
                        stringResource(R.string.update_channel_preview),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            },
        ) {
            Text(youHave, style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
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

/*
 * The settings section, in three parts so each sits on its own segment of the settings list: the
 * installed version with the manual check, the "check on start" switch, and the update channel.
 * The switch and the channel only exist where updates are enabled ([UpdateViewModel.enabled]).
 */

/** Installed version, update state and the manual check (or the update button when one is available). */
@Composable
fun UpdateStatus(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_version, viewModel.currentVersion), style = MaterialTheme.typography.bodyLarge)
        if (!viewModel.enabled) {
            Text(stringResource(R.string.update_disabled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        when (val s = state) {
            UpdateState.Checking -> Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium)
            is UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodyMedium)
            is UpdateState.Failed -> if (s.release == null) Text(stringResource(errorText(s.error)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        val pending = state.release
        if (pending != null && state !is UpdateState.Downloading) {
            Button(onClick = viewModel::reopen) {
                Text(if (state is UpdateState.Ready) stringResource(R.string.update_install) else stringResource(R.string.update_to, pending.version))
            }
        } else OutlinedButton(onClick = viewModel::checkNow, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
            Text(stringResource(R.string.update_check_now))
        }
    }
}

/** The "check for updates on start" switch, drawn by the caller's [switchRow]. */
@Composable
fun UpdateAutoCheck(viewModel: UpdateViewModel, switchRow: @Composable (label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) -> Unit) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    switchRow(stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_hint), prefs.autoUpdateCheck) { viewModel.setAuto(it) }
}

/** Stable or Preview: one of two, so a connected button group, with what the chosen channel means. */
@Composable
fun UpdateChannelChoice(viewModel: UpdateViewModel) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_channel), style = MaterialTheme.typography.bodyLarge)
        org.southtyrol.transit.design.ConnectedToggleGroup(
            options = org.southtyrol.transit.data.UpdateChannel.entries, selected = prefs.updateChannel,
            onSelect = { if (it != prefs.updateChannel) viewModel.setChannel(it) },
            label = { stringResource(if (it == org.southtyrol.transit.data.UpdateChannel.STABLE) R.string.update_channel_stable else R.string.update_channel_preview) },
            uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (prefs.updateChannel == org.southtyrol.transit.data.UpdateChannel.PREVIEW) R.string.update_channel_preview_hint else R.string.update_channel_stable_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
