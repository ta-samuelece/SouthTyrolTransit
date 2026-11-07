package org.southtyrol.transit.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.southtyrol.transit.BuildConfig
import org.southtyrol.transit.R
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.whatsnew.BundledWhatsNew
import org.southtyrol.transit.whatsnew.WhatsNewReadSheet
import javax.inject.Inject

/*
 * The Play build's side of the store seam described at [UpdateSettingsRows]. Play installs updates itself,
 * so the build has no updater; instead it shows the new version's cards once, after Play updated it.
 */

/**
 * Whether to show "New in X" for [version]: only after a real update (Android sets the update time past
 * the install time; a fresh install, a restore included, has them equal), once per version, and only for a
 * version with cards - a hotfix without cards stays quiet.
 */
internal fun shouldShowNewIn(firstInstall: Long, lastUpdate: Long, seenVersion: String, version: String, hasCards: Boolean): Boolean =
    lastUpdate > firstInstall && seenVersion != version && hasCards

@HiltViewModel
class NewInVersionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
    bundled: BundledWhatsNew,
) : ViewModel() {
    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show.asStateFlow()

    init {
        viewModelScope.launch {
            val version = BuildConfig.VERSION_NAME
            val seen = settings.current().notesSeenVersion
            if (seen == version) return@launch
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val hasCards = bundled.load().highlights.isNotEmpty()
            // Recorded before showing: the sheet is a courtesy, and must never come back on every start.
            settings.setNotesSeenVersion(version)
            _show.value = shouldShowNewIn(info.firstInstallTime, info.lastUpdateTime, seen, version, hasCards)
        }
    }

    fun dismiss() {
        _show.value = false
    }
}

/** "New in X" after Play updated the app; true while it shows. */
@Composable
fun showUpdateStartPrompt(): Boolean {
    val viewModel: NewInVersionViewModel = hiltViewModel()
    val show by viewModel.show.collectAsStateWithLifecycle()
    if (show) WhatsNewReadSheet(onDismiss = viewModel::dismiss, heroTitle = stringResource(R.string.update_new_in_hero))
    return show
}

/** Only the installed version: Play decides about updates, so there is nothing to switch. */
@Composable
fun updateSettingsRows(@Suppress("UNUSED_PARAMETER") switchRow: SwitchRowContent): UpdateSettingsRows = UpdateSettingsRows(
    status = {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.update_from_play), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },
    options = emptyList(),
)
