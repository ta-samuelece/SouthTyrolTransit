package org.southtyrol.transit.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStatusTest {
    @Test fun firstRefreshFailingIsNotLoading() {
        assertEquals(WidgetStatus.LOAD_FAILED, WidgetState.status("it:22021:468", updatedAt = null, failedAt = 100))
    }

    @Test fun statusFollowsTheLatestRefresh() {
        assertEquals(WidgetStatus.NO_STOP, WidgetState.status(null, null, 100))
        assertEquals(WidgetStatus.LOADING, WidgetState.status("k", null, null))
        assertEquals(WidgetStatus.UPDATED, WidgetState.status("k", 100, null))
        assertEquals(WidgetStatus.UPDATE_FAILED, WidgetState.status("k", 100, 200))
        assertEquals(WidgetStatus.UPDATED, WidgetState.status("k", 200, 100))
    }
}
