package com.wineverdict.app.data

import com.wineverdict.app.model.BottleQuery
import com.wineverdict.app.model.BottleUpdate
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.MoveRequest
import com.wineverdict.app.model.StorageLocation
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.formatMoney
import com.wineverdict.app.model.locationName
import java.text.Normalizer
import java.util.Locale

/**
 * What a [MoveRequest] resolves to against the cellar as it stands now.
 *
 * Nothing here writes. The card draws this, the user confirms it, and only then does the repository
 * move anything — a move the model proposed is a question until a person taps the button.
 */
data class MovePlan(
    val request: MoveRequest,
    /** The bottles the confirm button would move: stocked, matching, and not already there. */
    val wines: List<Wine>,
    /** Matching bottles that already live at that place, so the card can say why they are absent. */
    val alreadyThere: List<Wine>,
    /** The place it already is; null means confirming would create it. */
    val location: StorageLocation?
) {
    val locationName: String get() = request.location.trim()
    val createsLocation: Boolean get() = location == null

    /** What the "from" filter means, or blank when the query does not have one. */
    val filterLabel: String get() = filterLabelOf(request.fromLocation)
}

/**
 * What a [BottleUpdate] resolves to: the bottles it would edit, and what the card says will change.
 *
 * Like a move, nothing is written here; [lines] is what the user reads before deciding.
 */
data class UpdatePlan(
    val request: BottleUpdate,
    val wines: List<Wine>
) {
    /** What the "from" filter means, or blank when the query does not have one. */
    val filterLabel: String get() = filterLabelOf(request.fromLocation)

    /** The field changes in words, with the old value when every matched bottle agrees on it. */
    val lines: List<String> get() = changeLines(request, wines)
}

/** Whether the query says anything about which bottles it means. */
fun BottleQuery.namesBottles(): Boolean =
    listOf(grape, type, producer, name, region, country, appellation, fromLocation)
        .any { it.isNotBlank() } ||
        vintageFrom != null || vintageTo != null

/**
 * The stocked bottles the query selects.
 *
 * This is the one matcher: a move and a field change see the same set, so a bottle cannot be selected
 * by one proposal and missed by the other.
 */
fun BottleQuery.matched(cellar: CellarData): List<Wine> =
    cellar.wines.filter { wine -> wine.quantity > 0 && matches(wine) && livesIn(cellar, wine) }

/**
 * Resolves the move against every bottle in the cellar.
 *
 * Null when the request cannot be used at all — no place named, or nothing to match on — because
 * both of those are a move nobody asked for. An empty [MovePlan.wines] is a real answer: it means
 * "nothing matches", which the card says out loud rather than silently doing nothing.
 */
fun MoveRequest.plan(cellar: CellarData): MovePlan? {
    val place = location.trim()
    if (place.isBlank() || !namesBottles()) return null
    val found = cellar.locations.firstOrNull { sameName(it.name, place) }
    val slot = this.slot.trim()
    val (there, elsewhere) = matched(cellar).partition { wine -> wine.livesAt(found, slot) }
    return MovePlan(request = this, wines = elsewhere, alreadyThere = there, location = found)
}

/**
 * Resolves the field change against every bottle in the cellar.
 *
 * Null when there is nothing to change, nothing to match on, or a value that cannot be a record
 * field — a negative price, a negative count, a year outside 1800–2100. Refusing the whole proposal
 * is the point: a value quietly dropped would be a change the card promised and the app did not make.
 */
fun BottleUpdate.plan(cellar: CellarData): UpdatePlan? {
    if (!hasChange || !namesBottles()) return null
    if ((price ?: 0.0) < 0.0) return null
    if ((quantity ?: 0) < 0) return null
    if (vintage != null && vintage !in 1800..2100) return null
    return UpdatePlan(request = this, wines = matched(cellar))
}

/** One bottle's place, as the detail page writes it. */
fun MovePlan.describe(wine: Wine, cellar: CellarData): String {
    val place = cellar.locationName(wine.locationId)
    return if (wine.slot.isBlank()) place else "$place / ${wine.slot}"
}

/**
 * Whether the bottle is where the ask says to look. A place name the cellar does not have matches
 * nothing, on purpose: a typo must fail loudly rather than widen to the whole shelf.
 */
private fun BottleQuery.livesIn(cellar: CellarData, wine: Wine): Boolean {
    val asked = fromLocation.trim()
    if (asked.isBlank()) return true
    val current = cellar.locations.firstOrNull { it.id == wine.locationId }?.name
    return if (unplaced(asked)) current == null else current != null && sameName(current, asked)
}

/** The words for "these bottles are in no location yet". */
private fun unplaced(word: String): Boolean =
    listOf("unassigned", "none", "nowhere", "no location", "without a location", "unplaced")
        .any { sameName(it, word) }

private fun filterLabelOf(fromLocation: String): String = when {
    fromLocation.isBlank() -> ""
    unplaced(fromLocation) -> "Only bottles with no location"
    else -> "Only bottles now at ${fromLocation.trim()}"
}

/**
 * The field changes in words.
 *
 * An old value is named only when every matched bottle agrees on it: "Price → USD 48.00 (was
 * USD 55.00)" is a fact, and "was 55, 60 and 72" would be noise on a card that already lists the
 * bottles.
 */
private fun changeLines(update: BottleUpdate, wines: List<Wine>): List<String> {
    val lines = mutableListOf<String>()
    val currency = wines.map { it.currency }.distinct().singleOrNull()
    update.slot?.let { slot ->
        val old = wines.map { it.slot }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
        val was = old?.let { " (was $it)" }.orEmpty()
        lines += if (slot.isBlank()) "Slot cleared$was" else "Slot → ${slot.trim()}$was"
    }
    update.price?.let { price ->
        val shown = if (currency != null) {
            formatMoney(price, currency)
        } else {
            String.format(Locale.US, "%,.2f", price)
        }
        val old = wines.mapNotNull { it.price }.distinct().singleOrNull()
        val was = old?.let {
            " (was ${if (currency != null) formatMoney(it, currency) else String.format(Locale.US, "%,.2f", it)})"
        }.orEmpty()
        lines += "Price → $shown$was"
    }
    update.quantity?.let { quantity ->
        val old = wines.map { it.quantity }.distinct().singleOrNull()
        lines += "Bottles → $quantity" + (old?.let { " (was $it)" }.orEmpty())
    }
    update.vintage?.let { vintage ->
        val old = wines.mapNotNull { it.vintage }.distinct().singleOrNull()
        lines += "Vintage → $vintage" + (old?.let { " (was $it)" }.orEmpty())
    }
    update.note?.let { note -> lines += "Add to my note: \u201c$note\u201d" }
    return lines
}

private fun BottleQuery.matches(wine: Wine): Boolean {
    if (grape.isNotBlank()) {
        // The label fallback matters: a bottle typed in by hand often carries its grape in the
        // cuvée and leaves the grapes list empty.
        val inGrapes = wine.grapes.joinToString(" / ").matchesWord(grape)
        if (!inGrapes && !wine.name.matchesWord(grape)) return false
    }
    if (type.isNotBlank()) {
        // A word that is not one of the seven types narrows nothing; reading it as Red would turn
        // "Cabernet" into a silent filter on every red bottle.
        val asked = WineType.entries.firstOrNull { sameName(it.label, type) || sameName(it.name, type) }
        if (asked != null && wine.type != asked) return false
    }
    if (producer.isNotBlank() && !wine.producer.matchesWord(producer) && !wine.name.matchesWord(producer)) {
        return false
    }
    if (name.isNotBlank() && !wine.name.matchesWord(name) && !wine.producer.matchesWord(name)) return false
    if (region.isNotBlank() && !wine.region.matchesWord(region) && !wine.appellation.matchesWord(region)) {
        return false
    }
    if (country.isNotBlank() && !wine.country.matchesWord(country)) return false
    if (appellation.isNotBlank() &&
        !wine.appellation.matchesWord(appellation) &&
        !wine.region.matchesWord(appellation)
    ) {
        return false
    }
    // A range excludes non-vintage bottles: a wine with no year cannot be said to fall in it. The
    // ends are read into locals because an interface property has no smart cast.
    val from = vintageFrom
    val to = vintageTo
    if (from != null || to != null) {
        val year = wine.vintage ?: return false
        if (from != null && year < from) return false
        if (to != null && year > to) return false
    }
    return true
}

/** Whether this bottle is already where the request wants it. A blank slot means the whole place. */
private fun Wine.livesAt(location: StorageLocation?, slot: String): Boolean =
    location != null && locationId == location.id && (slot.isBlank() || this.slot.trim() == slot)

private fun String.matchesWord(word: String): Boolean {
    val needle = fold(word)
    return needle.isNotBlank() && fold(this).contains(needle)
}

private fun fold(raw: String): String =
    Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .trim()

private fun sameName(a: String, b: String): Boolean = fold(a) == fold(b) && fold(a).isNotBlank()