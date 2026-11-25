package com.vinoteca.app.util

/**
 * Just enough CSV for the files wine apps export: quoted fields, escaped quotes, embedded
 * commas and newlines, CRLF line endings, and comma / semicolon / tab delimiters.
 */
object Csv {

    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val candidates = listOf(',', ';', '\t', '|')
        val best = candidates.maxByOrNull { delimiter -> countOutsideQuotes(sample, delimiter) } ?: ','
        return if (countOutsideQuotes(sample, best) > 0) best else ','
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var quoted = false
        var count = 0
        line.forEach { ch ->
            when {
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> count++
            }
        }
        return count
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            when {
                quoted -> when {
                    ch == '"' && i + 1 < source.length && source[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    ch == '"' -> quoted = false
                    else -> field.append(ch)
                }
                ch == '"' -> quoted = true
                ch == delimiter -> {
                    row.add(field.toString())
                    field.clear()
                }
                ch == '\r' -> Unit
                ch == '\n' -> {
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filter { cells -> cells.any { it.isNotBlank() } }
    }
}