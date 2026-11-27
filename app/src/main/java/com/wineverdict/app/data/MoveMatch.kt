package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.MoveRequest
import com.wineverdict.app.model.StorageLocation
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.locationName
import java.text.Normalizer

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
    val filterLabel: String
        get() = when {
            request.fromLocation.isBlank() -> ""
            unplaced(request.fromLocation) -> "Only bottles with no location"
            else -> "Only bottles now at ${request.fromLocation.trim()}"
        }
}

/** Whether the query says anything about which bottles it means. */
fun MoveRequest.namesBottles(): Boolean =
    listOf(grape, type, producer, name, region, country, appellation, fromLocation)
        .any { it.isNotBlank() } ||
        vintageFrom != null || vintageTo != null

/**
 * Resolves the query against every bottle in the cellar.
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
    val matched = cellar.wines.filter { wine ->
        wine.quantity > 0 && matches(wine) && livesIn(cellar, wine)
    }
    val (there, elsewhere) = matched.partition { wine -> wine.livesAt(found, slot) }
    return MovePlan(request = this, wines = elsewhere, alreadyThere = there, location = found)
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
private fun MoveRequest.livesIn(cellar: CellarData, wine: Wine): Boolean {
    val asked = fromLocation.trim()
    if (asked.isBlank()) return true
    val current = cellar.locations.firstOrNull { it.id == wine.locationId }?.name
    return if (unplaced(asked)) current == null else current != null && sameName(current, asked)
}

/** The words for "these bottles are in no location yet". */
private fun unplaced(word: String): Boolean =
    listOf("unassigned", "none", "nowhere", "no location", "without a location", "unplaced")
        .any { sameName(it, word) }

private fun String.matchesWord(word: String): Boolean {
    val needle = fold(word)
    return needle.isNotBlank() && fold(this).contains(needle)
}

private fun MoveRequest.matches(wine: Wine): Boolean {
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
    // A range excludes non-vintage bottles: a wine with no year cannot be said to fall in it.
    if (vintageFrom != null || vintageTo != null) {
        val year = wine.vintage ?: return false
        if (vintageFrom != null && year < vintageFrom) return false
        if (vintageTo != null && year > vintageTo) return false
    }
    return true
}

/** Whether this bottle is already where the request wants it. A blank slot means the whole place. */
private fun Wine.livesAt(location: StorageLocation?, slot: String): Boolean =
    location != null && locationId == location.id && (slot.isBlank() || this.slot.trim() == slot)

private fun fold(raw: String): String =
    Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .trim()

private fun sameName(a: String, b: String): Boolean = fold(a) == fold(b) && fold(a).isNotBlank()
