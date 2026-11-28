package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.locationName
import java.text.Normalizer

/**
 * The one matcher behind the cellar's search and the cellar book's.
 *
 * Both boxes compared the words exactly as they were stored, so `rose` found nothing in a cellar
 * full of Rosé and "St. Estèphe" was only findable with the stop and the accent that the person who
 * added the bottle happened to type. Folding the text first answers what was meant; one copy of the
 * rule here is what keeps the two boxes, and the book's bottle picker, agreeing.
 *
 * Nothing stored is rewritten: folding happens at the moment of the search, so the card, the bottle's
 * page and the backup keep the accents of the name.
 */
object Search {

    /** The accents an NFD decomposition leaves behind as separate characters. */
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * An apostrophe is an elision, not a break: "d'Yquem" folds to one word, so both `dyquem` and
     * `d'yquem` find it.
     */
    private val APOSTROPHE = Regex("['\u2019\u02BC]")

    /** Everything else that is not a letter or a digit separates words: stops, commas, dashes, quotes. */
    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, accents off, punctuation as a space, one space between words. */
    fun fold(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return plain.replace(APOSTROPHE, "").lowercase().replace(SEPARATOR, " ").trim()
    }

    /**
     * Whether every word of [query] appears in [fields].
     *
     * An empty query — or one that is nothing but punctuation, which folds away to nothing — matches
     * everything, because an empty box is the whole shelf rather than nothing. A part of a word still
     * matches, so a half-remembered name is enough, and every word has to appear, so a second word
     * narrows rather than widens.
     */
    fun matches(fields: List<String>, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val haystack = fold(fields.joinToString(" "))
        return words.all { haystack.contains(it) }
    }

    /**
     * What the cellar's box reads on a bottle: the label, where it is from, the grapes, the shelf and
     * slot it lives in, its taste words and the user's own notes.
     */
    fun wineFields(wine: Wine, data: CellarData): List<String> = listOf(
        wine.producer,
        wine.name,
        wine.region,
        wine.country,
        wine.appellation,
        wine.grapes.joinToString(" "),
        wine.slot,
        data.locationName(wine.locationId),
        wine.flavorTags.joinToString(" "),
        wine.myNotes
    )
}
