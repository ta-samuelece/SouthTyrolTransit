package org.southtyrol.transit.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.model.MapBase

/** In memory: the file-backed DataStore cannot replace its file on Windows outside Android. */
private class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(state.value).also { state.value = it }
}

class BackupTest {
    private fun settings() = SettingsRepository(MemoryPreferences())

    @Test fun restoreKeepsSetUpWorkAndDropsDeviceHistory() = runBlocking {
        val settings = settings()
        settings.setTheme(ThemeMode.DARK)
        settings.setAmoledDark(true)
        settings.setAppIcon("NIGHT_ROUTE")
        settings.setAlertNotifications(true)
        settings.setSkippedUpdate("0.2.0")
        settings.setMapBase(MapBase.AERIAL)
        val user = FakeUserDao()
        user.save(SavedRow("STOP", "it:22021:468", "Bolzano"))
        user.touch(RecentRow(SavedRepository.KIND_PLACE, "x", "Merano", "", "", 1))
        user.cache(CacheRow("efa:addinfo", ByteArray(1), 1))
        user.markNotified(NotifiedAlertRow("a1", 1))

        RestoreCleanup.apply(settings, user)

        val s = settings.current()
        assertEquals(ThemeMode.DARK, s.theme)
        assertTrue(s.amoledDark)
        assertEquals("NIGHT_ROUTE", s.appIcon)
        assertEquals(MapBase.AERIAL, s.mapBase)
        // The notification permission is not restored, so notices start off.
        assertFalse(s.alertNotifications)
        assertEquals("", s.skippedUpdate)
        assertEquals(1, user.savedRows.value.size)
        assertTrue(user.recentRows.value.isEmpty())
        assertTrue(user.cacheRows.isEmpty())
        assertTrue(user.notifiedAlerts().isEmpty())
    }
}
