package org.southtyrol.transit.whatsnew

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The icons a release's highlight cards may name. The sheet is drawn by the version already installed, so
 * a name added later shows as [SPARKLE] on older builds. Keep `docs/release-notes/icons.txt` in step: the
 * release script checks against it and a test compares the two.
 */
enum class WhatsNewIcon(val key: String) {
    SPARKLE("sparkle"),
    PLACE("place"),
    BUS("bus"),
    TRAIN("train"),
    MAP("map"),
    LAYERS("layers"),
    ROUTE("route"),
    BIKE("bike"),
    WALK("walk"),
    CAR("car"),
    SEARCH("search"),
    STAR("star"),
    NOTIFICATION("notification"),
    ALERT("alert"),
    WIDGET("widget"),
    LANGUAGE("language"),
    SPEED("speed"),
    DOWNLOAD("download"),
    FIX("fix"),
    PALETTE("palette"),
    TICKET("ticket"),
    SCHEDULE("schedule"),
    ;

    companion object {
        fun fromKey(key: String): WhatsNewIcon = entries.firstOrNull { it.key == key } ?: SPARKLE
    }
}

/** One card as published, with its text per language code. */
@Serializable
data class Highlight(
    val icon: String = "",
    val title: Map<String, String> = emptyMap(),
    val body: Map<String, String> = emptyMap(),
)

/** What a release says about itself: `whats-new.json` on GitHub, the same content bundled in the build. */
@Serializable
data class WhatsNew(
    val highlights: List<Highlight> = emptyList(),
    val notes: Map<String, String> = emptyMap(),
) {
    /** The text for [language], English where that language is missing; a card without a title is dropped. */
    fun inLanguage(language: String): LocalizedWhatsNew = LocalizedWhatsNew(
        highlights = highlights.mapNotNull { card ->
            val title = card.title.pick(language) ?: return@mapNotNull null
            LocalizedHighlight(WhatsNewIcon.fromKey(card.icon), title, card.body.pick(language).orEmpty())
        },
        notes = notes.pick(language).orEmpty(),
    )

    private fun Map<String, String>.pick(language: String): String? =
        this[language]?.takeIf { it.isNotBlank() } ?: this[FALLBACK_LANGUAGE]?.takeIf { it.isNotBlank() }
}

private const val FALLBACK_LANGUAGE = "en"

data class LocalizedHighlight(val icon: WhatsNewIcon, val title: String, val body: String)

data class LocalizedWhatsNew(val highlights: List<LocalizedHighlight>, val notes: String)

object WhatsNewParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null for anything unreadable: the sheet then falls back to the plain release notes. */
    fun parse(text: String): WhatsNew? = runCatching { json.decodeFromString<WhatsNew>(text) }.getOrNull()
}

/** The running version's notes, copied into the build's assets from `docs/release-notes/` by Gradle. */
@Singleton
class BundledWhatsNew @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun load(): WhatsNew = withContext(Dispatchers.IO) {
        WhatsNew(
            highlights = asset("highlights.json")?.let(WhatsNewParser::parse)?.highlights.orEmpty(),
            notes = LANGUAGES.mapNotNull { language -> asset("notes.$language.md")?.let { language to it } }.toMap(),
        )
    }

    private fun asset(name: String): String? =
        runCatching { context.assets.open("whats-new/$name").bufferedReader().use { it.readText() } }.getOrNull()

    private companion object {
        val LANGUAGES = listOf("en", "de", "it")
    }
}
