package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * One bottle that was opened: what it was, the day, and the answer given at the time.
 *
 * A separate record from the bottle, because a bottle is one thing and an evening is another. The
 * same wine drunk twice in a year deserves two entries, and the second answer is about the second
 * night rather than a correction of the first.
 *
 * It exists because nothing else did. **Drank one** took a bottle off the shelf and left no trace
 * beyond a number going down, so the one question everybody actually asks — what did I drink last
 * week — had no answer to give, and Ask could only say it had no record.
 */
@Serializable
data class Drink(
    val id: String = UUID.randomUUID().toString(),
    /** The wine it came from, so a bottle's page can say when it was last opened. */
    val wineId: String = "",
    val title: String = "",
    val type: String = "",
    val region: String = "",
    val price: Double? = null,
    val currency: String = "USD",
    /** The four-word answer at the time, not the bottle's rating now. Null when Skip was tapped. */
    val rating: Int? = null,
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
    /**
     * How the bottle left the shelf: [DRANK] for **Drank one**, [COUNT] for the minus button.
     *
     * Kept because the two are not the same claim. One says a bottle was opened and what it was
     * like; the other says the number was wrong by one. Both belong in the record — a bottle taken
     * out by the minus button is still a bottle that left the cellar — but only one of them has a
     * verdict, and a history that pretended otherwise would put words in the user's mouth.
     */
    val via: String = DRANK
) {
    companion object {
        const val DRANK = "drank"
        const val COUNT = "count"
    }
}

/**
 * Every bottle that has been drunk, newest first.
 *
 * Hand-written JSON like the cellar and the lists: a few hundred drinks is a small document, and the
 * whole history is filtered in memory. Capped rather than grown for ever — a log nobody prunes is a
 * file that eventually costs more than it answers.
 */
class DrinkLog(context: Context) {

    private val file = File(context.filesDir, "drinks.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val _drinks = MutableStateFlow<List<Drink>>(emptyList())
    val drinks: StateFlow<List<Drink>> = _drinks.asStateFlow()

    fun load() {
        if (!file.exists()) return
        runCatching { json.decodeFromString<List<Drink>>(file.readText()) }
            .onSuccess { loaded -> _drinks.value = loaded.sortedByDescending { it.at } }
    }

    /**
     * Records the bottle, with whatever the sheet collected. Written even when the sheet was skipped:
     * the drink happened, and "no verdict" is a real answer rather than a missing entry.
     */
    fun log(wine: Wine, rating: Int?, note: String): Drink = record(wine, rating, note, Drink.DRANK)

    /**
     * A bottle taken out with the minus button.
     *
     * No sheet is shown for that tap, so no verdict is invented: the entry says the count changed,
     * which is the whole of what happened. Nothing is returned when there was nothing to remove.
     */
    fun logCount(wine: Wine): Drink? =
        if (wine.quantity <= 0) null else record(wine, null, "", Drink.COUNT)

    private fun record(wine: Wine, rating: Int?, note: String, via: String): Drink {
        val drink = Drink(
            wineId = wine.id,
            title = wine.title,
            type = wine.type.label,
            region = wine.region.ifBlank { wine.country },
            price = wine.price,
            currency = wine.currency,
            rating = rating,
            note = note.trim(),
            via = via
        )
        write((_drinks.value + drink).sortedByDescending { it.at }.take(MAX_DRINKS))
        return drink
    }

    /** Takes an entry back — the undo of a tap that was not meant. */
    fun remove(id: String) {
        write(_drinks.value.filterNot { it.id == id })
    }

    /** The most recent night a bottle was opened, for the bottle's own page. */
    fun lastDrinkOf(wineId: String): Drink? =
        _drinks.value.firstOrNull { it.wineId == wineId }

    /** The record as Ask reads it; blank when nothing has been drunk yet. */
    fun digest(limit: Int = 20, now: Long = System.currentTimeMillis()): String =
        drinkDigest(_drinks.value, now, limit)

    private fun write(next: List<Drink>) {
        _drinks.value = next
        runCatching { file.writeText(json.encodeToString(next)) }
    }

    private companion object {
        /** Five years of a bottle most days; older entries are dropped rather than kept for ever. */
        const val MAX_DRINKS = 2_000
    }
}

/**
 * The log as a prompt section: the day each bottle was opened, in words the question will use, and
 * the totals that answer "last week" without the model doing arithmetic on dates.
 *
 * "today" and "yesterday" are worked out here rather than left to the model, because that is exactly
 * the subtraction a language model gets wrong from a raw timestamp.
 */
internal fun drinkDigest(drinks: List<Drink>, now: Long, limit: Int = 20): String {
    if (drinks.isEmpty()) return ""
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val ordered = drinks.sortedByDescending { it.at }
    val lines = ordered.take(limit).map { drink ->
        val day = Instant.ofEpochMilli(drink.at).atZone(zone).toLocalDate()
        val said = when (day) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> "$day (${ChronoUnit.DAYS.between(day, today)} days ago)"
        }
        buildString {
            append("- ").append(said).append(": ").append(drink.title)
            val where = listOf(drink.type, drink.region)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (where.isNotBlank()) append(" (").append(where).append(")")
            if (drink.via == Drink.COUNT) {
                // Said here rather than left to be read as a verdict the user never gave.
                append(" — count adjusted, no verdict")
            } else {
                append(" — ").append(Verdict.label(drink.rating) ?: "no verdict")
            }
            if (drink.note.isNotBlank()) append(", \"").append(drink.note.trim()).append("\"")
        }
    }
    val week = ordered.filter { it.at >= now - 7 * DAY_MS }
    val month = ordered.filter { it.at >= now - 30 * DAY_MS }
    val loved = week.filter { Verdict.of(it.rating) == Verdict.LOVED }.map { it.title }
    val rejected = week.filter { Verdict.of(it.rating) == Verdict.NOT_FOR_ME }.map { it.title }
    val summary = buildString {
        append("Totals: ${week.size} bottle(s) in the last 7 days, ")
        append("${month.size} in the last 30, ${ordered.size} recorded altogether.")
        if (loved.isNotEmpty()) append(" Loved this week: ${loved.joinToString("; ")}.")
        if (rejected.isNotEmpty()) append(" Not for me this week: ${rejected.joinToString("; ")}.")
    }
    return summary + "\n" + lines.joinToString("\n")
}

/**
 * How a night is said out loud: today, yesterday, then the date. The same words the chat uses, so a
 * record read on the phone and a record read by Ask agree.
 */
fun dayWords(
    at: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}
