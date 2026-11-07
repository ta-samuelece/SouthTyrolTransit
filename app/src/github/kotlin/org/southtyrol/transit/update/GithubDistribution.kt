package org.southtyrol.transit.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * The GitHub build's side of the store seam described at [UpdateSettingsRows]: the app updates itself from
 * GitHub Releases.
 */

/** Offers a newer GitHub release on start and follows its download and install; true while the sheet shows. */
@Composable
fun showUpdateStartPrompt(): Boolean {
    val updates: UpdateViewModel = hiltViewModel()
    UpdatePrompt(updates)
    val showing by updates.prompt.collectAsStateWithLifecycle()
    return showing
}

/** The version with the manual check; the start-up check and the channel where updates are enabled. */
@Composable
fun updateSettingsRows(switchRow: SwitchRowContent): UpdateSettingsRows {
    val updates: UpdateViewModel = hiltViewModel()
    return UpdateSettingsRows(
        status = { UpdateStatus(updates) },
        options = if (updates.enabled) listOf({ UpdateAutoCheck(updates, switchRow) }, { UpdateChannelChoice(updates) }) else emptyList(),
    )
}
