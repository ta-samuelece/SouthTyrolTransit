package org.southtyrol.transit.feature.common

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * The state of a search field whose [query] a ViewModel owns: every edit goes to [onQuery], and a [query] the
 * ViewModel changes itself (cleared on close, reset on open) is copied into the field. A [query] that only
 * echoes what the field reported is ignored, so text typed since then is never overwritten. Rotation and
 * process death are the ViewModel's to survive; the field is seeded from [query] again.
 */
@Composable
internal fun rememberQueryFieldState(query: String, onQuery: (String) -> Unit): TextFieldState {
    val state = remember { TextFieldState(query) }
    val reported = remember { mutableStateOf(query) }
    val currentOnQuery by rememberUpdatedState(onQuery)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != reported.value) {
                reported.value = text
                currentOnQuery(text)
            }
        }
    }
    LaunchedEffect(query) {
        if (query != reported.value) {
            reported.value = query
            state.setTextAndPlaceCursorAtEnd(query)
        }
    }
    return state
}
