package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine

/**
 * What the app already knows about a wine standing on the page in front of you.
 *
 * "Have I had this?" is the first question at a table and the one thing a menu cannot answer. The
 * cellar knows what is on the shelf and what has been finished, the drink log knows when it was
 * opened, and both are matched the way every record is matched: producer, cuvée and vintage, folded
 * for case and accents.
 *
 * The distinction the menu card keeps: a wine you own and a wine you have drunk are different
 * facts, and a bottle on the shelf is not one you have opened.
 */
data class Prior(
    /** Bottles on hand right now. Zero for a wine that was had, finished, or only kept. */
    val bottles: Int = 0,
    /** Whether any record exists at all — kept, finished, or rated. */
    val onRecord: Boolean = false,
    /** When it was last opened, from the drink log. 0 means never recorded as drunk. */
    val drankAt: Long = 0,
    /** The word on record for it, from the bottle or from the night it was drunk. */
    val rating: Int? = null
) {
    /** The verdict word, when one was ever given. */
    val verdict: String? get() = Verdict.label(rating)

    /** What the card says, in the fewest words that are still true. */
    val summary: String
        get() = when {
            bottles > 0 && drankAt > 0 ->
                "In your cellar · you had it ${dayPhrase(drankAt)}"
            bottles > 0 ->
                "In your cellar · ${bottles} " + if (bottles == 1) "bottle" else "bottles"
            drankAt > 0 -> "You had this ${dayPhrase(drankAt)}"
            onRecord -> "On record, no bottles"
            else -> "You have had this"
        }
}

/**
 * The match. Null when the app knows nothing about the wine, which is the common case and must stay
 * silent rather than say "not in your cellar" under every line on a menu.
 */
fun priorFor(wine: Wine, cellar: List<Wine>, drinks: List<Drink>): Prior? {
    val identity = InsightStore.key(wine)
    // The row with bottles wins: a wine owned twice over — a finished record and a fresh one — is
    // "in your cellar", not "gone".
    val record = cellar
        .filter { InsightStore.key(it) == identity }
        .maxByOrNull { it.quantity }
    val lastDrink = drinks
        .filter { it.wineId == record?.id || it.wineId == wine.id }
        .maxByOrNull { it.at }
    if (record == null && lastDrink == null) return null
    return Prior(
        bottles = (record?.quantity ?: 0).coerceAtLeast(0),
        onRecord = record != null,
        drankAt = lastDrink?.at ?: 0,
        // The bottle's own word first: a verdict edited on the bottle page is the latest answer.
        rating = record?.rating ?: lastDrink?.rating
    )
}

/** "today", "yesterday", "on 24 Nov 2026" — the date only when it cannot be said as a day. */
private fun dayPhrase(at: Long): String {
    val words = dayWords(at)
    return if (words == "today" || words == "yesterday") words else "on $words"
}
