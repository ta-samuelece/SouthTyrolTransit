package org.southtyrol.transit.model

import java.text.Normalizer
import java.util.Locale

/** Language codes supported by the app. Ladin is `lld` (ISO 639-3); EFA uses `ld1`/`ld2` for its variants. */
object Languages {
    val supported = listOf("en", "de", "it", "lld")

    fun normalize(tag: String): String = when (val lang = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
        "ld1", "ld2", "lad" -> "lld"
        else -> lang
    }

    /** EFA answers in de/it/en; Ladin speakers in South Tyrol are served best by Italian or German. */
    fun efa(tag: String): String = when (normalize(tag)) {
        "de" -> "de"
        "it", "lld" -> "it"
        else -> "en"
    }

    /**
     * The EFA StopFinder only identifies places when asked in German or Italian (with `en` every
     * query is "notidentified", verified 4 Oct 2026). Place names are bilingual anyway.
     */
    fun efaStopFinder(tag: String): String = if (normalize(tag) in setOf("it", "lld")) "it" else "de"

    fun fallbacks(tag: String): List<String> = when (normalize(tag)) {
        "lld" -> listOf("lld", "de", "it", "en")
        "de" -> listOf("de", "en", "it")
        "it" -> listOf("it", "en", "de")
        else -> listOf(normalize(tag), "en", "it", "de")
    }
}

/**
 * Picks the best translation of a multilingual value: requested language, then language-neutral
 * text (empty key), then the network's operating languages and English, then anything.
 */
fun Map<String, String>.localized(language: String): String {
    val values = entries.filter { it.value.isNotBlank() }.associate { Languages.normalize(it.key) to it.value }
    val wanted = Languages.fallbacks(language)
    values[wanted.first()]?.let { return it }
    values[""]?.let { return it }
    for (lang in wanted.drop(1)) values[lang]?.let { return it }
    return values.values.firstOrNull().orEmpty()
}

object TextNormalizer {
    private val marks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Case- and accent-insensitive key used for searching and de-duplication. */
    fun key(text: String): String =
        separators.replace(marks.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""), " ").trim()

    /** Converts the minimal HTML used in EFA AddInfo texts into plain text. */
    fun stripHtml(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&amp;", "&")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n").trim()
}

/** Merges alerts that describe the same disruption, preferring GTFS-RT data and unioning scopes. */
object AlertDeduplicator {
    private fun texts(alert: ServiceAlert) = alert.headers.values.map(TextNormalizer::key).filter { it.length > 3 }.toSet()

    fun merge(alerts: List<ServiceAlert>): List<ServiceAlert> {
        val groups = mutableListOf<MutableList<ServiceAlert>>()
        for (alert in alerts.sortedBy { it.source.ordinal }) {
            val text = texts(alert)
            val group = groups.firstOrNull { list ->
                val head = list.first()
                head.id == alert.id || (text.isNotEmpty() && text.intersect(texts(head)).isNotEmpty() && overlaps(head, alert) && sameScope(head, alert))
            }
            if (group != null) group += alert else groups += mutableListOf(alert)
        }
        return groups.map { list ->
            list.reduce { a, b ->
                a.copy(
                    headers = b.headers + a.headers,
                    descriptions = b.descriptions.filterValues { it.isNotBlank() } + a.descriptions.filterValues { it.isNotBlank() },
                    routes = a.routes + b.routes,
                    stops = a.stops + b.stops,
                    trips = a.trips + b.trips,
                    lineNames = a.lineNames + b.lineNames,
                    periods = (a.periods + b.periods).distinct(),
                    urls = b.urls + a.urls,
                )
            }
        }
    }

    private fun sameScope(a: ServiceAlert, b: ServiceAlert): Boolean {
        val aEmpty = a.routes.isEmpty() && a.stops.isEmpty() && a.lineNames.isEmpty()
        val bEmpty = b.routes.isEmpty() && b.stops.isEmpty() && b.lineNames.isEmpty()
        if (aEmpty || bEmpty) return true
        return a.routes.intersect(b.routes).isNotEmpty() || a.stops.intersect(b.stops).isNotEmpty() ||
            a.lineNames.intersect(b.lineNames).isNotEmpty() || a.source != b.source
    }

    private fun overlaps(a: ServiceAlert, b: ServiceAlert): Boolean {
        if (a.periods.isEmpty() || b.periods.isEmpty()) return true
        return a.periods.any { (s1, e1) ->
            b.periods.any { (s2, e2) -> (s1 == null || e2 == null || s1.isBefore(e2)) && (s2 == null || e1 == null || s2.isBefore(e1)) }
        }
    }
}
