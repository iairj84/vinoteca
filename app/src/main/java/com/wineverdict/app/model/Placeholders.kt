package com.wineverdict.app.model

/**
 * The words a spreadsheet writes when it has no value.
 *
 * A CSV column is not optional, so an app with no vineyard still fills the Designation column —
 * with "Unknown", "N/A" or "None". Read as a name, that is a wine which does not exist: the cellar
 * here has "Ridge Geyserville Unknown" on most of its imported cards, because the importer appended
 * every non-blank designation to the wine name.
 *
 * One list, used in three places: the import ignores a filler designation, a stored name has the
 * same word trimmed off its end, and the place and grape fields read filler as blank so the next
 * lookup can fill them rather than treating a placeholder as a fact.
 */
object Placeholders {

    private val WORDS = setOf("unknown", "n/a", "n.a", "na", "none", "null", "-", "—", "–", "?")

    /** True when the whole cell is filler rather than a value. A blank cell is not filler. */
    fun isFiller(text: String): Boolean {
        val clean = text.trim().trim('"', '\'').lowercase().trimEnd('.', ',', ':', ';', '!')
        return clean in WORDS
    }

    /**
     * [text] without filler words at its end, so a name imported with the placeholder appended
     * reads as the wine it is. A name that was nothing but filler becomes blank — the producer
     * still names the bottle — and the word inside a real name ("Unknown Wine Co.") is left alone.
     */
    fun stripTrailing(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && isFiller(words.last())) words.removeAt(words.lastIndex)
        return words.joinToString(" ").trim().trimEnd(',', ';', ':', '-', '—', '–', '·').trim()
    }
}
