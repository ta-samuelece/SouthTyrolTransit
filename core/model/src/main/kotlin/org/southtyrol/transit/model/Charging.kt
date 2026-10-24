package org.southtyrol.transit.model

/*
 * Charging stations from the Open Data Hub (EChargingStation + EChargingPlug). Operators describe their
 * points in different vocabularies (OCPI, OICP, plain numbers); everything is normalised here so the data
 * source only parses and the screens only draw.
 */

enum class ConnectorType { CCS, TYPE_2, CHADEMO, TYPE_1, DOMESTIC, OTHER }

enum class ChargingStatus { FREE, IN_USE, OUT_OF_ORDER, UNKNOWN }

/** One connector; [cable] is true when a cable is attached, false for a socket, null when not said. */
data class ChargingConnector(val type: ConnectorType, val powerKw: Double?, val dc: Boolean, val cable: Boolean?)

/** One charging point (an EVSE); [connector] is null when the operator does not describe it. */
data class ChargingPoint(val id: String, val connector: ChargingConnector?, val status: ChargingStatus)

data class ChargingDetails(
    val address: String?,
    val city: String?,
    val openAllDay: Boolean,
    /** On private ground but open to everyone (hotels, company car parks). */
    val privateWithPublicAccess: Boolean,
    val rfid: Boolean,
    val paymentUrl: String?,
    val points: List<ChargingPoint>,
) {
    /** At least one DC point of [Charging.FAST_KW] or more. */
    val fast: Boolean get() = points.any { Charging.isFast(it.connector) }

    /** Every point is reported out of order. */
    val outOfService: Boolean get() = points.isNotEmpty() && points.all { it.status == ChargingStatus.OUT_OF_ORDER }

    val free: Int get() = points.count { it.status == ChargingStatus.FREE }

    /** Some point has a known status, so the free count means something. */
    val hasStatus: Boolean get() = points.any { it.status != ChargingStatus.UNKNOWN }
}

/** Points of one connector type, power and cable, as one row of the station sheet. */
data class ChargingGroup(val connector: ChargingConnector?, val statuses: List<ChargingStatus>) {
    val free: Int get() = statuses.count { it == ChargingStatus.FREE }
    val outOfOrder: Int get() = statuses.count { it == ChargingStatus.OUT_OF_ORDER }
    val hasStatus: Boolean get() = statuses.any { it != ChargingStatus.UNKNOWN }
}

object Charging {
    const val FAST_KW = 50.0

    fun isFast(connector: ChargingConnector?): Boolean = connector != null && connector.dc && (connector.powerKw ?: 0.0) >= FAST_KW

    /**
     * A point's status from any operator's vocabulary; null means the point no longer exists (OCPI
     * REMOVED, PLANNED) and is dropped. Unrecognised values, including route220's bare 0 and 1 whose
     * meaning is not documented, are UNKNOWN.
     */
    fun status(raw: String?): ChargingStatus? = when (raw?.trim()?.uppercase()) {
        "AVAILABLE" -> ChargingStatus.FREE
        "CHARGING", "OCCUPIED", "BLOCKED", "RESERVED" -> ChargingStatus.IN_USE
        "INOPERATIVE", "OUTOFORDER", "OUTOFSERVICE", "FAULTED" -> ChargingStatus.OUT_OF_ORDER
        "REMOVED", "PLANNED" -> null
        else -> ChargingStatus.UNKNOWN
    }

    /** OCPI connector standards (IEC_62196_T2_COMBO …) and the older outlet codes (Type2Mennekes, CCS …). */
    fun connectorType(raw: String?): ConnectorType {
        val v = raw?.uppercase()?.replace(" ", "")?.replace("-", "_") ?: return ConnectorType.OTHER
        return when {
            "COMBO" in v || "CCS" in v -> ConnectorType.CCS
            "CHADEMO" in v -> ConnectorType.CHADEMO
            "T2" in v || "TYPE2" in v || "MENNEKES" in v -> ConnectorType.TYPE_2
            "T1" in v || "TYPE1" in v -> ConnectorType.TYPE_1
            "DOMESTIC" in v || "SCHUKO" in v -> ConnectorType.DOMESTIC
            else -> ConnectorType.OTHER
        }
    }

    /** Hydrogen dispensers ("700 bar small vehicles") share the charging feed; they are not chargers. */
    fun isHydrogen(outletCode: String?): Boolean = outletCode != null && Regex("""\d+\s*bar\b""", RegexOption.IGNORE_CASE).containsMatchIn(outletCode)

    /** Groups points by connector, power and cable, most powerful first; undescribed points last. */
    fun groups(points: List<ChargingPoint>): List<ChargingGroup> =
        points.groupBy { it.connector }
            .map { (connector, members) -> ChargingGroup(connector, members.map { it.status }) }
            .sortedWith(compareBy<ChargingGroup> { it.connector == null }.thenByDescending { it.connector?.powerKw ?: 0.0 }.thenBy { it.connector?.type })

    /**
     * The station's title: its name when it reads as one, else its address. Operators often name stations
     * by an internal code ("BZ_P_VITTORIA", "IT*BEC*EH000137*1"); null when nothing readable is left.
     */
    fun title(name: String, address: String?): String? {
        val n = name.trim()
        if (n.isNotEmpty() && !isCode(n)) return n
        return address?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** No lower-case letter and an underscore, asterisk or digit: an identifier, not a name. */
    fun isCode(name: String): Boolean = name.none { it.isLowerCase() } && name.any { it == '_' || it == '*' || it.isDigit() }

    /** "BOLZANO - BOZEN" reads "Bolzano - Bozen"; names already in mixed case are kept. */
    fun cityName(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.any { it.isLowerCase() }) return v
        return v.lowercase().split(' ').joinToString(" ") { word -> word.split('-').joinToString("-") { it.replaceFirstChar(Char::titlecase) } }
    }
}
