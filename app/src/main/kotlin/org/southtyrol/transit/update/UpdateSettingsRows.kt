package org.southtyrol.transit.update

import androidx.compose.runtime.Composable

/**
 * What the app does about its own updates depends on where it was installed from, so each store flavor
 * defines two functions with the same signatures, which shared code calls without knowing the store:
 *
 * - `showUpdateStartPrompt(): Boolean` draws the start-up sheet - the update offer on GitHub, "New in X"
 *   after an update on Play - and returns true while it shows, so other start-up prompts wait for it.
 * - `updateSettingsRows(switchRow): UpdateSettingsRows` gives Settings' Updates group its rows.
 *
 * Both live in `update/` of `src/github/` and `src/play/`.
 */
class UpdateSettingsRows(
    /** The installed version, and what this build says about updates. */
    val status: @Composable () -> Unit,
    /** Further rows below "What's new in this version", each drawn on its own segment. */
    val options: List<@Composable () -> Unit>,
)

/** A settings switch row, drawn by Settings: label, hint, state and change handler. */
typealias SwitchRowContent = @Composable (label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) -> Unit
