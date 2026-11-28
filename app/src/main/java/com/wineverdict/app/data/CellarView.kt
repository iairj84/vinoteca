package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.DrinkStatus
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.drinkStatus

/**
 * What the cellar's filter row offers, and what each chip keeps.
 *
 * The row used to stop at Sparkling, so a rosé, an orange wine, a Sauternes and a port had one home
 * between them: All. The four styles a bottle already records now have a chip of their own.
 *
 * Order is the point of the first six. **Drink up**, **To buy**, **Ready now**, **Loved** and **No
 * bottles** are questions about what to do with the shelf; the seven styles are how the collection
 * is filed. The strip scrolls, so a chip past the fourth is a chip a thumb goes looking for: adding
 * the new styles in the middle pushed **No bottles** — the only way to reach a wine with none left —
 * off the end of it. The questions keep the front of the row for that reason, and All still opens the
 * shelf.
 */
enum class CellarFilter(val label: String) {
    ALL("All"),
    DRINK_UP("Drink up"),
    TO_BUY("To buy"),
    READY("Ready now"),
    LOVED("Loved"),
    EMPTY("No bottles"),
    RED("Red"),
    WHITE("White"),
    ROSE("Rosé"),
    SPARKLING("Sparkling"),
    ORANGE("Orange"),
    DESSERT("Dessert"),
    FORTIFIED("Fortified");

    /**
     * The style this chip is about, or null for a question about the collection rather than a type.
     *
     * The screen reads it to say "No rosé bottles" rather than "Nothing matches", and a test reads
     * it to check that every style the model can write onto a bottle has a chip that finds it.
     */
    val type: WineType?
        get() = when (this) {
            RED -> WineType.RED
            WHITE -> WineType.WHITE
            ROSE -> WineType.ROSE
            SPARKLING -> WineType.SPARKLING
            ORANGE -> WineType.ORANGE
            DESSERT -> WineType.DESSERT
            FORTIFIED -> WineType.FORTIFIED
            ALL, DRINK_UP, TO_BUY, READY, LOVED, EMPTY -> null
        }

    fun matches(wine: Wine): Boolean = when (this) {
        ALL -> true
        // Only stock is a decision: a wine kept off a list, or one all drunk, is not a bottle to open.
        DRINK_UP -> wine.quantity > 0 && wine.drinkStatus() in DrinkUp.DUE
        TO_BUY -> wine.toBuy
        // A bottle you may open is not one you must, so Ready now is READY and PEAK without the
        // wines past their window that Drink up is for.
        READY -> wine.quantity > 0 && wine.drinkStatus() in READY_NOW
        LOVED -> Verdict.liked(wine)
        EMPTY -> wine.quantity <= 0
        // A style chip is a record, not a decision, so it shows a bottle with none left as well: a
        // rosé you finished is still a rosé you want to remember.
        RED, WHITE, ROSE, SPARKLING, ORANGE, DESSERT, FORTIFIED -> wine.type == type
    }

    companion object {
        private val READY_NOW = listOf(DrinkStatus.READY, DrinkStatus.PEAK)

        /**
         * A chip read back from a saved screen state.
         *
         * The label is what survives rotation and a process kill, so a label this build no longer
         * has falls back to the whole shelf rather than to an empty page.
         */
        fun of(label: String?): CellarFilter = entries.firstOrNull { it.label == label } ?: ALL
    }
}

/**
 * The orders the list can be read in.
 *
 * [HOUSE] is the one the cellar has always had and stays the default: the bottles with a heart, the
 * ones actually on the shelf, the nearest the end of a window, then the producer. The other four
 * answer a different question each — what did I just buy, who made this, how old is it, and what is
 * the most valuable thing here.
 *
 * Every one of them leaves a bottle with no such fact at the bottom rather than at the top, because
 * a missing price is not a cheap one and a non-vintage is not the oldest wine in the cellar.
 */
enum class CellarSort(val label: String) {
    HOUSE("House order"),
    ADDED("Recently added"),
    PRODUCER("Producer A–Z"),
    VINTAGE("Newest vintage"),
    PRICE("Highest price");

    fun comparator(): Comparator<Wine> = when (this) {
        HOUSE -> CellarView.HOUSE_ORDER
        // A shopping trip in one tap: what came off the shelf last is at the top.
        ADDED -> compareByDescending<Wine> { it.addedAt }
            .thenBy { it.producer.lowercase() }
            .thenBy { it.vintage ?: 9999 }
        // A producer's own line reads newest first, which is how a shelf of one grower is written.
        PRODUCER -> compareBy<Wine> { it.producer.lowercase() }
            .thenByDescending { it.vintage ?: Int.MIN_VALUE }
            .thenBy { it.name.lowercase() }
        VINTAGE -> compareByDescending<Wine> { it.vintage ?: Int.MIN_VALUE }
            .thenBy { it.producer.lowercase() }
        PRICE -> compareByDescending<Wine> { it.price ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.producer.lowercase() }
    }

    companion object {
        /** A sort read back from a saved screen state; a label this build lacks is the house order. */
        fun of(label: String?): CellarSort = entries.firstOrNull { it.label == label } ?: HOUSE
    }
}

object CellarView {

    /**
     * The bottles the list shows, in the order it shows them.
     *
     * The search box is the cellar's own — it narrows what you own, it never adds a wine — and it
     * reads the same fields it always has, so the box and the chip compose instead of one replacing
     * the other. The order is the last thing applied, so a sort never changes which bottles a filter
     * and a search kept.
     */
    fun visible(
        wines: List<Wine>,
        data: CellarData,
        query: String,
        filter: CellarFilter,
        sort: CellarSort = CellarSort.HOUSE
    ): List<Wine> = wines
        .filter { filter.matches(it) }
        .filter { Search.matches(Search.wineFields(it, data), query) }
        .sortedWith(sort.comparator())

    /**
     * The order the list has always had: a heart first, then what is actually on the shelf, then the
     * bottle nearest the end of its window, then the producer's name.
     *
     * It lives here rather than inside the screen so a sort control can be measured against it
     * instead of replacing it by accident.
     */
    val HOUSE_ORDER: Comparator<Wine> =
        compareByDescending<Wine> { it.favorite }
            .thenByDescending { it.quantity > 0 }
            .thenByDescending { it.drinkStatus().ordinal }
            .thenBy { it.producer.lowercase() }
            .thenBy { it.vintage ?: 9999 }
}
