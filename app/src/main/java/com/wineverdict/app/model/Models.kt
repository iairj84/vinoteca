package com.wineverdict.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Year
import java.util.UUID

@Serializable
enum class WineType(val label: String) {
    RED("Red"),
    WHITE("White"),
    ROSE("Rosé"),
    SPARKLING("Sparkling"),
    DESSERT("Dessert"),
    FORTIFIED("Fortified"),
    ORANGE("Orange");

    companion object {
        fun from(raw: String?): WineType {
            val v = raw?.lowercase()?.trim().orEmpty()
            if (v.isBlank()) return RED
            entries.firstOrNull { it.name.lowercase() == v }?.let { return it }
            return when {
                v.contains("spark") || v.contains("champ") || v.contains("bubb") || v.contains("cava") -> SPARKLING
                v.contains("ros") -> ROSE
                v.contains("white") || v.contains("blanc") || v.contains("bianco") || v.contains("blanco") -> WHITE
                v.contains("orange") || v.contains("ramato") -> ORANGE
                v.contains("dessert") || v.contains("sweet") || v.contains("tokaji") || v.contains("sautern") -> DESSERT
                v.contains("fortif") || v.contains("sherry") || v.contains("madeira") || v.contains("port") -> FORTIFIED
                else -> RED
            }
        }
    }
}

@Serializable
data class StorageLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val note: String = ""
)

@Serializable
data class Wine(
    val id: String = UUID.randomUUID().toString(),
    val producer: String = "",
    val name: String = "",
    val vintage: Int? = null,
    val type: WineType = WineType.RED,
    val country: String = "",
    val region: String = "",
    val appellation: String = "",
    val grapes: List<String> = emptyList(),
    val alcohol: Double? = null,
    val barcode: String = "",
    val quantity: Int = 1,
    val locationId: String? = null,
    val slot: String = "",
    val price: Double? = null,
    val currency: String = "USD",
    val drinkFrom: Int? = null,
    val drinkTo: Int? = null,
    val expertNotes: String = "",
    val flavorTags: List<String> = emptyList(),
    /** The pages a reference note was read from, space-separated. Empty when nobody checked it. */
    val notesSource: String = "",
    /** When a lookup last ran for this bottle, whether or not it found anything. 0 = never asked. */
    val notesCheckedAt: Long = 0,
    /**
     * When the ABV was last looked up; 0 = never asked. Kept apart from [notesCheckedAt] on
     * purpose: an ABV miss must not stop the notes lookup, and a notes check must not block a
     * second attempt at the number the alcohol gate needs.
     */
    val alcoholCheckedAt: Long = 0,
    /**
     * When the drinking window was last looked up; 0 = never asked. Its own stamp because a page
     * can describe a wine and print no window, which is a different thing from nobody looking: a
     * miss that was asked for is a miss worth asking for again once a page exists.
     */
    val windowCheckedAt: Long = 0,
    /** When the place — country, region, appellation — was last looked up; 0 = never asked. */
    val placeCheckedAt: Long = 0,
    /** When the grapes were last looked up; 0 = never asked. */
    val grapesCheckedAt: Long = 0,
    val myNotes: String = "",
    val rating: Int? = null,
    val favorite: Boolean = false,
    val fitScore: Int? = null,
    val fitReason: String = "",
    val source: String = "manual",
    /**
     * The label as photographed, as a file name under the cellar's label directory. Blank for a
     * bottle typed in, imported, or added without a scan — the record is the app's reading of a
     * label, and this is the label itself, which is what to check when a vintage looks wrong.
     */
    val photo: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * "Why it tastes this way", as the model wrote it. Null until it has been asked for, which is
     * why it is a nullable field with a default: every record from before this step loads as null
     * and the page simply has nothing to show.
     */
    val insightJson: String? = null,
    val insightModel: String = "",
    /** When the insight was written; 0 = never asked. */
    val insightDate: Long = 0
) {
    val title: String
        get() {
            val base = listOf(name, producer)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val year = vintage?.toString()
            return when {
                year != null && base.isNotBlank() -> "$year $base"
                year != null -> year
                base.isNotBlank() -> base
                else -> "Unnamed wine"
            }
        }

    val subtitle: String
        get() = listOf(type.label, region.ifBlank { country }, grapes.joinToString(", "))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    val drinkWindowLabel: String
        get() = when {
            drinkFrom != null && drinkTo != null -> "$drinkFrom–$drinkTo"
            drinkFrom != null -> "from $drinkFrom"
            drinkTo != null -> "until $drinkTo"
            else -> "unknown"
        }
}

enum class DrinkStatus(val label: String) {
    NONE("No window"),
    HOLD("Hold"),
    READY("Drink now"),
    PEAK("At peak"),
    PAST("Past peak")
}

/**
 * What a bottle was like, in four words instead of a 1–5 count.
 *
 * The count was a scale the app never read: every threshold treated the number as a bucket — 4 or
 * more was loved, 1 or 2 was disliked, 3 was silence — so 1 and 2 were the same answer, 4 and 5
 * were the same answer, and no step between them was ever asked for. These are the four buckets
 * the app actually has, and they store as the old numbers (1, 3, 4, 5) so a bottle rated before
 * this change keeps its place with no migration. A stored 2 still reads as [NOT_FOR_ME]: it was one
 * when the number was the only way to say it.
 */
enum class Verdict(val rating: Int, val label: String) {
    NOT_FOR_ME(1, "Not for me"),
    FINE(3, "Fine"),
    GOOD(4, "Good"),
    LOVED(5, "Loved");

    companion object {
        fun of(rating: Int?): Verdict? = when {
            rating == null || rating < 1 -> null
            rating <= 2 -> NOT_FOR_ME
            rating == 3 -> FINE
            rating == 4 -> GOOD
            else -> LOVED
        }

        fun label(rating: Int?): String? = of(rating)?.label

        /** The word for a bottle: its rating, or the old heart when nothing was ever rated. */
        fun labelFor(wine: Wine): String? =
            label(wine.rating) ?: if (wine.favorite) LOVED.label else null

        /** Good and Loved are what the loved list, the Loved filter and the brief read. */
        fun liked(wine: Wine): Boolean = wine.favorite || (wine.rating ?: 0) >= GOOD.rating

        /** Not for me is the only "did not like it" answer. */
        fun disliked(wine: Wine): Boolean = (wine.rating ?: 0) in 1..2
    }
}

fun Wine.drinkStatus(currentYear: Int = Year.now().value): DrinkStatus {
    val from = drinkFrom
    val to = drinkTo
    if (from == null && to == null) return DrinkStatus.NONE
    if (from != null && currentYear < from) return DrinkStatus.HOLD
    if (to != null && currentYear > to) return DrinkStatus.PAST
    if (from != null && to != null && to > from) {
        val lastThird = (to - from) / 3
        if (lastThird > 0 && currentYear >= to - lastThird) return DrinkStatus.PEAK
    }
    return DrinkStatus.READY
}

fun Wine.isDrinkableNow(currentYear: Int = Year.now().value): Boolean {
    val status = drinkStatus(currentYear)
    return status == DrinkStatus.READY || status == DrinkStatus.PEAK
}

@Serializable
data class CellarData(
    val wines: List<Wine> = emptyList(),
    val locations: List<StorageLocation> = emptyList()
)

data class CellarStats(
    val bottles: Int,
    val distinctWines: Int,
    val readyToDrink: Int,
    val invested: Double,
    val currency: String
)

fun CellarData.stats(): CellarStats {
    val priced = wines.filter { it.price != null }
    return CellarStats(
        bottles = wines.sumOf { it.quantity },
        // Stock, not pages: a wine kept off a list has no bottles and must not inflate the count
        // of what the cellar holds.
        distinctWines = wines.count { it.quantity > 0 },
        readyToDrink = wines.filter { it.quantity > 0 && it.isDrinkableNow() }.size,
        invested = priced.sumOf { (it.price ?: 0.0) * it.quantity },
        currency = wines.mapNotNull { it.currency.takeIf { c -> c.isNotBlank() } }.firstOrNull() ?: "USD"
    )
}

fun CellarData.locationName(id: String?): String =
    locations.firstOrNull { it.id == id }?.name ?: "Unassigned"

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String = "user",
    val text: String = "",
    @Transient val imageBase64: String? = null,
    val at: Long = System.currentTimeMillis(),
    /**
     * The move this answer asked for, as a query the app resolves against the whole cellar. Null on
     * every answer that asked for nothing, which is most of them. The marker line is not kept in
     * [text], so the card is the only place the request is shown.
     */
    val moveRequest: MoveRequest? = null,
    /**
     * The field change this answer proposed, as a query plus the new values. Null when it proposed
     * nothing, or proposed a move instead: one card per answer.
     */
    val update: BottleUpdate? = null,
    /** How many bottles the confirmed proposal wrote. 0 means nothing has been written yet. */
    val movedCount: Int = 0
)

fun defaultLocations(): List<StorageLocation> = listOf(
    StorageLocation(name = "Main rack"),
    StorageLocation(name = "Wine fridge"),
    StorageLocation(name = "Everyday rack")
)

fun formatMoney(value: Double?, currency: String): String {
    if (value == null) return "—"
    return String.format(java.util.Locale.US, "%s %,.2f", currency, value)
}