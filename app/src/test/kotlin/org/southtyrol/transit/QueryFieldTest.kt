package org.southtyrol.transit

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.southtyrol.transit.feature.common.rememberQueryFieldState

/** The search fields' state mirrors a query a ViewModel owns: edits go up, the ViewModel's own changes come down. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class QueryFieldTest {
    @get:Rule val compose = createComposeRule()

    private var query by mutableStateOf("")
    private val reported = mutableListOf<String>()
    private lateinit var field: TextFieldState

    private fun show(initial: String = "") {
        query = initial
        compose.setContent {
            field = rememberQueryFieldState(query) { reported += it; query = it }
            BasicTextField(field, Modifier.testTag("field"))
        }
        compose.waitForIdle()
    }

    @Test fun startsWithTheQueryWithoutReportingIt() {
        show("Bozen")
        assertEquals("Bozen", field.text.toString())
        assertEquals(emptyList<String>(), reported)
    }

    @Test fun typingReportsTheText() {
        show()
        compose.onNodeWithTag("field").performTextInput("Meran")
        compose.waitForIdle()
        assertEquals("Meran", reported.last())
        assertEquals("Meran", query)
        compose.onNodeWithTag("field").performTextClearance()
        compose.waitForIdle()
        assertEquals("", reported.last())
    }

    @Test fun queryChangedOutsideReachesTheField() {
        show("Bozen")
        compose.onNodeWithTag("field").performTextInput(" Bahnhof")
        compose.waitForIdle()
        assertEquals("Bozen Bahnhof", query)
        // As MapSearchViewModel.close() or PlacesViewModel.reset() do: the ViewModel empties the query.
        query = ""
        compose.waitForIdle()
        assertEquals("", field.text.toString())
        query = "Brixen"
        compose.waitForIdle()
        assertEquals("Brixen", field.text.toString())
        // The field's copy of an outside change is not reported back.
        assertEquals(listOf("Bozen Bahnhof"), reported)
    }
}
