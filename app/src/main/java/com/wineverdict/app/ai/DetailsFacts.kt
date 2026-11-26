package com.wineverdict.app.ai

/**
 * The facts one lookup can read beside the tasting note, parsed from what the model printed.
 *
 * Years are the one thing here that can be wrong in a way nobody notices. "2027-2038" is a window,
 * "2028" alone may be a single drinking year, and a page that says nothing must not become a
 * plausible guess. So the window is only the years the answer printed, in the order a window goes,
 * and anything else is no window at all. The place and grape lists have the same rule: filler
 * ("unknown", "none", "-") is dropped rather than stored as if it were a name.
 */
internal object DetailsFacts {

    /** The two ends of a drinking window as printed, or nulls when the line names no usable year. */
    fun window(raw: String): Pair<Int?, Int?> {
        val text = raw.lowercase().replace('–', '-').replace('—', '-').trim()
        val years = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")
            .findAll(text)
            .map { it.value.toInt() }
            .filter { it in 1950..2100 }
            .toList()
        if (years.isEmpty()) return null to null
        if (years.size >= 2) {
            val from = years.min()
            val to = years.max()
            return if (from < to) from to to else from to null
        }
        val only = years.first()
        return when {
            Regex("\\b(until|through|thru|by|last)\\b").containsMatchIn(text) -> null to only
            Regex("\\b(from|onwards?|onward)\\b").containsMatchIn(text) || text.endsWith("-") ->
                only to null
            // A lone year with no word around it is one drinking year, not an open range.
            else -> only to only
        }
    }

    /**
     * The pair as it may be stored: each end inside the range a wine can be alive in, and never with
     * the ends reversed. A muddled answer is no window rather than a swapped one.
     */
    fun sane(from: Int?, to: Int?): Pair<Int?, Int?> {
        val start = from?.takeIf { it in 1950..2100 }
        val end = to?.takeIf { it in 1950..2100 }
        if (start != null && end != null && end < start) return null to null
        return start to end
    }

    /** A place name as the answer printed it, or empty when it printed none. */
    fun place(raw: String, limit: Int): String {
        val clean = raw.trim().trim('.', ',', ';', ':', '"', '\'').replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > limit) return ""
        val filler = clean == "-" || clean.equals("unknown", true) || clean.equals("none", true) ||
            clean.equals("n/a", true)
        return if (filler) "" else clean
    }

    /** The grapes an answer listed, in its order, without the filler. */
    fun grapes(raw: String): List<String> = raw
        .split(',', ';', '/', '·', '|')
        .map { it.trim().trim('.', '"', '\'', '(', ')') }
        .filter { it.isNotBlank() && it.length <= 30 }
        .filterNot { it == "-" || it.equals("unknown", true) || it.equals("none", true) }
        .distinct()
        .take(6)
}
