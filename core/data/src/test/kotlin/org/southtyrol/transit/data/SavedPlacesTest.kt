package org.southtyrol.transit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SavedPlacesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val bozen = Place("66000468", "Bozen, Bahnhof", PlaceType.STOP, Point(46.49, 11.35), "Bozen", "it:22021:468")
    private val meran = Place("", "Laurinstraße 8", PlaceType.ADDRESS, Point(46.67, 11.16), "Meran")

    private fun own(items: List<SavedItem>) = items.filter { it.kind == SavedKind.PLACE && !it.isHomeOrWork }.map { it.label }

    @Test fun ownPlacesKeepTheirIconAndOrder() = runBlocking {
        var now = 1_000L
        val saved = SavedRepository(FakeUserDao()) { Instant.ofEpochMilli(now++) }
        saved.savePlace(SavedRepository.HOME, "Home", bozen)
        saved.addPlace("Nonna", "heart", meran)
        saved.addPlace("Climbing", "sport", bozen.copy(id = "2", name = "Salewa Cube"))
        saved.addPlace("Theatre", "theatre", bozen.copy(id = "3", name = "Laurin Theatre"))
        // New places go to the end, not to the top.
        assertEquals(listOf("Nonna", "Climbing", "Theatre"), own(saved.saved.first()))
        assertEquals("heart", saved.saved.first().single { it.label == "Nonna" }.icon)

        val theatre = saved.saved.first().single { it.label == "Theatre" }
        saved.movePlace(theatre.id, up = true)
        assertEquals(listOf("Nonna", "Theatre", "Climbing"), own(saved.saved.first()))
        saved.movePlace(theatre.id, up = true)
        saved.movePlace(theatre.id, up = true) // already first: nothing happens
        assertEquals(listOf("Theatre", "Nonna", "Climbing"), own(saved.saved.first()))

        // Renaming keeps the place where it is.
        saved.savePlace(theatre.id, "Stadttheater", theatre.place!!, "star")
        assertEquals(listOf("Stadttheater", "Nonna", "Climbing"), own(saved.saved.first()))
        assertEquals("star", saved.saved.first().single { it.id == theatre.id }.icon)
    }

    @Test fun rowsSavedBeforePositionsMoveToo() = runBlocking {
        val user = FakeUserDao()
        // Before positions were written every row had 0 and the newest came first.
        user.save(SavedRow(SavedKind.PLACE.name, "place:a", "A", "", Codec.place(bozen), created = 1))
        user.save(SavedRow(SavedKind.PLACE.name, "place:b", "B", "", Codec.place(meran), created = 2))
        val saved = SavedRepository(user)
        saved.movePlace("place:a", up = true)
        assertEquals(listOf("place:a" to 0, "place:b" to 1), user.savedRows.value.sortedBy { it.position }.map { it.id to it.position })
    }

    @Test fun recognisesASavedPlace() {
        val item = SavedItem(SavedKind.PLACE, "place:x", "Nonna", meran.name, Codec.place(meran), "heart")
        assertTrue(item.isPlace(meran.copy(locality = "Meran/Merano")))
        assertFalse(item.isPlace(meran.copy(point = Point(46.0, 11.0))))
        assertFalse(item.isPlace(bozen))
        assertTrue(SavedItem(SavedKind.PLACE, SavedRepository.WORK, "Work", "", Codec.place(bozen)).isHomeOrWork)
    }

    /** A `user.db` written by version 1 - also what a backup from an older app restores - opens with its items. */
    @Test fun migratesVersion1() {
        val file = context.getDatabasePath("user-v1.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/org.southtyrol.transit.data.UserDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO saved (kind, id, label, subtitle, payload, created, position) VALUES ('PLACE', 'home', 'Home', 'Bozen', '', 1, 0)")
            db.version = 1
        }
        val room = Room.databaseBuilder(context, UserDatabase::class.java, file.absolutePath)
            .addMigrations(UserDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val row = runBlocking { room.user().saved(SavedKind.PLACE.name) }.single()
            assertEquals("home", row.id)
            assertEquals("", row.icon)
        } finally {
            room.close()
        }
    }
}
