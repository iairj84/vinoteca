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
    val myNotes: String = "",
    val rating: Int? = null,
    val favorite: Boolean = false,
    val fitScore: Int? = null,
    val fitReason: String = "",
    val source: String = "manual",
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
        distinctWines = wines.size,
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
    val at: Long = System.currentTimeMillis()
)

fun defaultLocations(): List<StorageLocation> = listOf(
    StorageLocation(name = "Main rack"),
    StorageLocation(name = "Wine fridge"),
    StorageLocation(name = "Everyday rack")
)

fun formatMoney(value: Double?, currency: String): String {
    if (value == null) return "—"
    return String.format(java.util.Locale.US, "%s %.2f", currency, value)
}