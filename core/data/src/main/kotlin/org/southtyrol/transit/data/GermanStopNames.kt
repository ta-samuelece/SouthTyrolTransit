package org.southtyrol.transit.data

/**
 * Cleans the German stop names EFA gives ([EfaXml.stopNames]) before they fill the timetable's missing
 * translations ([ScheduleStore.fillStopNames]). Names read "Locality, Stop" in both languages.
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
        val votes = german.mapNotNull { (key, name) ->
            val from = italian[key]?.let(::locality) ?: return@mapNotNull null
            val to = locality(name) ?: return@mapNotNull null
            from to to
        }.groupBy({ it.first }, { it.second })
        val renamed = votes.mapNotNull { (from, names) ->
            val counts = names.groupingBy { it }.eachCount()
            val best = counts.maxBy { it.value }
            if (best.key != from && best.value > (counts[from] ?: 0)) from to best.key else null
        }.toMap()
        return german.mapValues { (key, name) ->
            val local = locality(name)
            val replacement = local?.let { renamed[it] }
            // Without a locality in the timetable ("Touriseum"), one most stops rename is still known to be Italian.
            val italianLocality = italian[key]?.let(::locality)
            if (replacement != null && (italianLocality == null || local == italianLocality)) replacement + name.substring(local.length) else name
        }
    }

    /** "Bolzano" for "Bolzano, Piazza Walther"; null for a name without a locality. */
    private fun locality(name: String): String? = name.indexOf(", ").takeIf { it > 0 }?.let { name.substring(0, it) }
}
