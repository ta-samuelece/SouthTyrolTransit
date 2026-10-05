package org.southtyrol.transit.data

import java.io.Reader

/**
 * Streaming RFC 4180 reader tuned for large GTFS files: a single reusable char buffer, quoted fields
 * with commas/newlines/escaped quotes, CRLF or LF line endings and an optional UTF-8 BOM.
 */
class Csv(private val reader: Reader, bufferSize: Int = 1 shl 16) {
    private val buffer = CharArray(bufferSize)
    private var length = 0
    private var position = 0
    private var started = false
    private val cell = StringBuilder(64)

    private fun next(): Int {
        if (position == length) {
            length = reader.read(buffer)
            position = 0
            if (length <= 0) { length = 0; return -1 }
        }
        return buffer[position++].code
    }

    private fun peek(): Int {
        if (position == length) {
            length = reader.read(buffer)
            position = 0
            if (length <= 0) { length = 0; return -1 }
        }
        return buffer[position].code
    }

    /** Returns the next record, or null at end of input. Blank lines yield a single empty field. */
    fun row(): List<String>? {
        if (!started) { started = true; if (peek() == 0xFEFF) next() }
        var c = next()
        if (c == -1) return null
        val cells = ArrayList<String>(12)
        cell.setLength(0)
        var quoted = false
        while (true) {
            if (quoted) {
                when (c) {
                    -1 -> throw IllegalArgumentException("Unterminated CSV quote")
                    '"'.code -> if (peek() == '"'.code) { next(); cell.append('"') } else quoted = false
                    else -> cell.append(c.toChar())
                }
            } else {
                when (c) {
                    -1 -> { cells += cell.toString(); return cells }
                    '"'.code -> if (cell.isEmpty()) quoted = true else cell.append('"')
                    ','.code -> { cells += cell.toString(); cell.setLength(0) }
                    '\n'.code -> { cells += cell.toString(); return cells }
                    '\r'.code -> { if (peek() == '\n'.code) next(); cells += cell.toString(); return cells }
                    else -> cell.append(c.toChar())
                }
            }
            c = next()
        }
    }

    /** Iterates records as header-keyed rows without allocating a map per row. */
    inline fun forEachRecord(block: (Record) -> Unit) {
        val header = row() ?: return
        val record = Record(header.map { it.trim() })
        while (true) {
            val values = row() ?: break
            if (values.size == 1 && values[0].isEmpty()) continue
            record.values = values
            block(record)
        }
    }

    class Record(val header: List<String>) {
        private val index = header.withIndex().associate { it.value to it.index }
        var values: List<String> = emptyList()
        fun has(name: String) = name in index
        operator fun get(name: String): String = index[name]?.let { values.getOrNull(it) }?.trim().orEmpty()
        fun int(name: String, default: Int = 0): Int = get(name).toIntOrNull() ?: default
        fun double(name: String): Double = get(name).toDouble()
    }
}
