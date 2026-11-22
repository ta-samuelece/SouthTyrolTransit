package org.southtyrol.transit.data

import org.southtyrol.transit.model.TextNormalizer

/**
 * Cleans the German stop names EFA gives ([EfaXml.stopNames]) before they fill the timetable's missing
 * translations, and derives German trip destinations from them ([ScheduleStore.fillStopNames]). Names read
 * "Locality, Stop" in both languages.
 */
internal object GermanStopNames {
    /**
     * [german] by station key, with localities EFA left in Italian put into German. A few stops, mostly
     * stations and cable cars, come as "Bolzano, Rittner Seilbahn" while 167 other Bolzano stops say "Bozen".
     * An Italian locality (from [italian], the timetable's names by station key) is renamed only when most
     * of its stops give it one other German name, so names that are the same in both languages ("Corvara",
     * "Cavalese") stay as they are, even where a few unrelated stops disagree. A stop whose timetable name
     * has no locality takes the rename too.
     */
    fun corrected(italian: Map<String, String>, german: Map<String, String>): Map<String, String> {
        val renamed = renamedLocalities(italian.mapNotNull { (key, it) -> german[key]?.let { de -> it to de } })
        return german.mapValues { (key, name) ->
            val local = locality(name)
            val replacement = local?.let { renamed[it] }
            // Without a locality in the timetable ("Touriseum"), one most stops rename is still known to be Italian.
            val italianLocality = italian[key]?.let(::locality)
            if (replacement != null && (italianLocality == null || local == italianLocality)) replacement + name.substring(local.length) else name
        }
    }

    /** Italian localities most of whose stops ([pairs] of Italian and German names) carry one other German locality. */
    fun renamedLocalities(pairs: Collection<Pair<String, String>>): Map<String, String> =
        pairs.mapNotNull { (it, de) ->
            val from = locality(it) ?: return@mapNotNull null
            val to = locality(de) ?: return@mapNotNull null
            from to to
        }.groupBy({ it.first }, { it.second }).mapNotNull { (from, names) ->
            val counts = names.groupingBy { it }.eachCount()
            val best = counts.maxBy { it.value }
            if (best.key != from && best.value > (counts[from] ?: 0)) from to best.key else null
        }.toMap()

    /** A stop served by the trips with [headsign]: its Italian and German names, whether it ends one of them, and how many. */
    data class HeadsignStop(val headsign: String, val italian: String, val german: String, val last: Boolean, val trips: Int)

    /**
     * German destinations for the Italian [stops]' headsigns, which STA's feed no longer translates. A headsign
     * that names the trips' last stop takes its German name ("Lana Bivio Via Dogana" -> "Lana, Abzweigung Zollstraße").
     * Otherwise each " - " part must be a locality the trips pass through, alone or followed by a common word
     * ("Merano Stazione" -> "Meran Bahnhof", "Bronzolo - Laives" -> "Branzoll - Leifers"); the trips' own stops
     * decide which "S. Valentino" is meant, and [renamed] fills in a locality EFA left in Italian there. A
     * headsign with any other part ("Bolzano per Viale Druso", "Navetta") is left out rather than half
     * translated.
     */
    fun headsigns(stops: List<HeadsignStop>, renamed: Map<String, String>): Map<String, String> =
        stops.groupBy { it.headsign }.mapNotNull { (headsign, served) ->
            val german = lastStop(headsign, served) ?: byParts(headsign, served, renamed)
            german?.takeIf { it != headsign }?.let { headsign to it }
        }.toMap()

    private fun lastStop(headsign: String, served: List<HeadsignStop>): String? {
        val stop = served.filter { it.last && key(it.italian) == key(headsign) }.maxByOrNull { it.trips } ?: return null
        val local = locality(stop.german) ?: return stop.german
        val place = stop.german.substring(local.length + 2)
        // "Tiso" ends at "Teis (Villnöss), Teis": a headsign without a locality gets none.
        return if (locality(stop.italian) == null) place else "${bare(local)}, $place"
    }

    private fun byParts(headsign: String, served: List<HeadsignStop>, renamed: Map<String, String>): String? {
        val localities = served.mapNotNull { s -> locality(s.italian)?.let { it to (locality(s.german) ?: return@mapNotNull null) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, names) -> bare(names.groupingBy { it }.eachCount().maxBy { it.value }.key) }
        fun place(italian: String): String? {
            val own = localities[italian]
            return if (own == null || key(own) == key(italian)) renamed[italian]?.let(::bare) ?: own else own
        }
        val parts = headsign.split(" - ").map { part ->
            val p = part.trim()
            place(p) ?: WORDS.firstNotNullOfOrNull { (word, german) ->
                if (p.endsWith(" $word")) place(p.removeSuffix(" $word"))?.let { "$it $german" } else null
            } ?: return null
        }
        return parts.joinToString(" - ")
    }

    /** Common destination words after a locality. */
    private val WORDS = listOf(
        "Autostazione" to "Busbahnhof", "Stazione" to "Bahnhof", "Centro" to "Zentrum",
        "Ospedale" to "Krankenhaus", "Paese" to "Dorf", "Funivia" to "Seilbahn",
    )

    /** "Bolzano" for "Bolzano, Piazza Walther"; null for a name without a locality. */
    private fun locality(name: String): String? = name.indexOf(", ").takeIf { it > 0 }?.let { name.substring(0, it) }

    /** "Steinmannwald" for "Steinmannwald (Leifers)": the municipality in brackets is too long for a destination. */
    private fun bare(locality: String): String = locality.replace(Regex("""\s*\([^)]*\)$"""), "")

    /** Compares names ignoring case, accents, spaces and punctuation ("Bolzano Autostazione" = "Bolzano, Autostazione"). */
    private fun key(name: String): String = TextNormalizer.key(name).filter { it.isLetterOrDigit() }
}
