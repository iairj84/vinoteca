package com.vinoteca.app.ai

import com.vinoteca.app.model.Wine
import com.vinoteca.app.model.WineType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class ScannedLabel(
    val wine: Wine,
    val fitScore: Int?,
    val fitReason: String,
    val flavorTags: List<String>,
    val priceEstimate: Double?,
    val confidence: String,
    val readNotes: String,
    val labelText: String = "",
    val wineFound: Boolean = true,
    /** "good", "fair" … from a shop shelf only; a list price is never a deal. */
    val valueVerdict: String = "unknown",
    /** Fields the model flagged as doubtful — shown as "To be sure" so the user knows what to recheck. */
    val uncertainFields: List<String> = emptyList(),
    /** The one photo or check that would settle the most, in the model's own words. */
    val nextTip: String = "",
    /** Per-glass price printed on a restaurant list; null elsewhere. Never a bottle price. */
    val glassPrice: Double? = null,
    /**
     * The model's order down a wine list: 1 = the bottle it would order first from this page.
     * A comparison between the lines, so it keeps meaning when `mytaste.md` is empty and every
     * `personal_fit` comes back neutral. Null everywhere but a list.
     */
    val listRank: Int? = null
) {
    val usable: Boolean
        get() = wineFound && (wine.name.isNotBlank() || wine.producer.isNotBlank())
}

/** Compact description of a first reading, fed back into the second-look prompt. */
fun ScannedLabel.digest(): String = buildString {
    appendLine("label_text: ${labelText.ifBlank { "(nothing transcribed)" }}")
    appendLine("producer: ${wine.producer}")
    appendLine("name: ${wine.name}")
    appendLine("vintage: ${wine.vintage ?: "null"}")
    appendLine("appellation: ${wine.appellation}")
    appendLine("region: ${wine.region}")
    appendLine("country: ${wine.country}")
    appendLine("grapes: ${wine.grapes.joinToString(", ")}")
    appendLine("type: ${wine.type.label}")
    appendLine("alcohol: ${wine.alcohol ?: "null"}")
    appendLine("barcode: ${wine.barcode}")
    appendLine("confidence: $confidence")
    append("read_notes: $readNotes")
}

/**
 * Fills what the first read could not see from the dedicated small-print pass, and only the blanks:
 * a figure already read off the front label wins. Fields the pass answered stop being listed as
 * uncertain, and its note replaces the old "photograph the back label" tip.
 */
fun ScannedLabel.withFinePrint(fine: ScannedLabel): ScannedLabel {
    val before = wine
    val after = before.copy(
        alcohol = before.alcohol ?: fine.wine.alcohol,
        vintage = before.vintage ?: fine.wine.vintage,
        appellation = before.appellation.ifBlank { fine.wine.appellation },
        region = before.region.ifBlank { fine.wine.region },
        country = before.country.ifBlank { fine.wine.country },
        grapes = before.grapes.ifEmpty { fine.wine.grapes },
        barcode = before.barcode.ifBlank { fine.wine.barcode }
    )
    val filled = buildList {
        if (before.alcohol == null && after.alcohol != null) add("alcohol")
        if (before.vintage == null && after.vintage != null) add("vintage")
        if (before.appellation.isBlank() && after.appellation.isNotBlank()) add("appellation")
        if (before.grapes.isEmpty() && after.grapes.isNotEmpty()) add("grapes")
    }
    return copy(
        wine = after,
        labelText = listOf(labelText, fine.labelText)
            .map { it.trim() }.filter { it.isNotBlank() }
            .joinToString("\n").take(1_500),
        uncertainFields = (uncertainFields - filled.toSet()).distinct(),
        nextTip = if (fine.uncertainFields.isEmpty()) "" else fine.nextTip.ifBlank { nextTip },
        confidence = when {
            confidence == "high" -> confidence
            after.alcohol != null && after.vintage != null && after.producer.isNotBlank() &&
                after.name.isNotBlank() -> "medium"
            else -> confidence
        }
    )
}

/** What the photo turned out to be. The model reads this off the frame, not the user. */
enum class Scene {
    BOTTLE, SHELF, LIST, OTHER;

    companion object {
        fun from(raw: String?): Scene = when (raw?.lowercase()?.trim()) {
            "bottle", "label", "single" -> BOTTLE
            "shelf", "shop", "store" -> SHELF
            "list", "menu", "restaurant" -> LIST
            else -> OTHER
        }
    }
}

/** One photo, resolved: the scene plus every bottle that could be read out of it. */
data class SceneScan(
    val scene: Scene,
    val bottles: List<ScannedLabel>,
    val notes: String
)

object ScanParsing {

    /** The one-word answer to "what is this photo?", with a tolerant read of anything chatty around it. */
    fun sceneWord(raw: String): Scene? {
        val word = Regex("[a-zA-Z]+").findAll(raw.lowercase()).map { it.value }
            .firstOrNull { it in setOf("bottle", "label", "shelf", "shop", "list", "menu", "other") }
        return when (word) {
            "bottle", "label" -> Scene.BOTTLE
            "shelf", "shop" -> Scene.SHELF
            "list", "menu" -> Scene.LIST
            "other" -> Scene.OTHER
            else -> null
        }
    }

    /**
     * The line-per-wine answer to a wine list.
     *
     * Fields: producer | name | vintage | region | grapes | type | bottle | glass | confidence.
     * A line that does not split into at least six fields is not a wine line and is dropped, and a
     * reply that stops early simply yields fewer wines instead of nothing at all.
     */
    /**
     * The `SCENE: list` line the list read now starts with. Null when the model skipped it, in
     * which case the scene is inferred from how many wines came back.
     */
    fun sceneLine(raw: String): Scene? =
        Regex("(?im)^[\\s*>-]*SCENE\\s*[:\\-]\\s*([a-zA-Z]+)")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.let { Scene.from(it) }

    fun parseListLines(raw: String, currencyFallback: String): List<ScannedLabel> =
        raw.lines()
            .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("\u2022 ").trim() }
            .filter { line -> line.count { it == '|' } >= 5 }
            .filterNot { line -> line.substringBefore('|').trim().equals("producer", true) }
            .distinct()
            .take(30)
            .mapNotNull { line ->
                lineFromFields(line.split('|').map { it.trim() }, currencyFallback)
            }

    private fun lineFromFields(parts: List<String>, currencyFallback: String): ScannedLabel? {
        fun raw(index: Int): String = parts.getOrNull(index)?.trim().orEmpty()
        fun text(index: Int): String = raw(index).takeIf { it.isNotBlank() && it != "-" }.orEmpty()
        fun number(index: Int): Double? {
            val value = text(index).replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return null
            return value.takeIf { it > 0.0 }
        }
        val year = java.time.LocalDate.now().year
        val producer = text(0)
        val name = text(1)
        if (producer.isBlank() && name.isBlank()) return null
        if (text(0).equals("producer", true)) return null
        val vintage = text(2).replace(Regex("[^0-9]"), "").toIntOrNull()?.takeIf { it in 1900..year }
        val grapes = text(4).split(';', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "-" }
        val bottle = number(6)?.takeIf { it < 5000.0 }
        // A "glass" that costs more than the bottle is the two figures swapped or one read twice.
        val glass = number(7)?.takeIf { it < 5000.0 }?.takeIf { bottle == null || it < bottle }
        val declared = text(8).lowercase()
        val rank = text(9).replace(Regex("[^0-9]"), "").toIntOrNull()?.takeIf { it in 1..24 }
        // The read's one-line case for this bottle's place on the page. It is what makes a ranked
        // card explain itself instead of carrying a bare "Best pick".
        val why = text(10).take(160)
        val confidence = when {
            declared.startsWith("hi") -> "high"
            declared.startsWith("lo") -> "low"
            else -> "medium"
        }
        val wine = Wine(
            producer = producer,
            name = name,
            vintage = vintage,
            type = WineType.from(text(5)),
            region = text(3),
            grapes = grapes,
            price = bottle,
            currency = currencyFallback,
            source = "wine-list"
        )
        return ScannedLabel(
            wine = wine,
            fitScore = null,
            fitReason = why,
            flavorTags = emptyList(),
            priceEstimate = bottle,
            confidence = confidence,
            readNotes = "",
            wineFound = true,
            valueVerdict = "unknown",
            glassPrice = glass,
            listRank = rank
        )
    }


    fun parseLabel(raw: String, currencyFallback: String): ScannedLabel =
        labelFrom(AiJson.objectFrom(raw), currencyFallback)

    /**
     * A frame of several bottles: the model returns an array, and each entry becomes a wine.
     * Anything without a name or producer is dropped — a frame full of guesses is worse than a
     * short honest list.
     */
    fun parseLabelFrame(raw: String, currencyFallback: String): List<ScannedLabel> {
        val root = AiJson.objectFrom(raw)
        val array = root["bottles"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            labelFrom(item, currencyFallback).takeIf { it.usable }
        }
    }

    /**
     * The whole frame: what the photo is, and every bottle read out of it.
     *
     * The scene comes from the model because it is in the photo, and the screen draws differently
     * for each one — a label card for "bottle", verdict cards for a shelf or a list. A reply that
     * says "other" but lists a bottle is treated as what it actually read.
     */
    fun parseScene(raw: String, currencyFallback: String): SceneScan {
        val root = AiJson.objectFrom(raw)
        val bottles = (root["bottles"] as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                labelFrom(obj, currencyFallback).takeIf { it.usable }
            }
            .orEmpty()
        val declared = Scene.from(root.str("scene"))
        val scene = when {
            // An empty reply still knows what it was looking at. Keeping the declared scene is what
            // lets the screen say "no wines read from that list" instead of "no readable bottles" —
            // the difference between a list that needs a second shot and a photo of a wall.
            bottles.isEmpty() -> declared
            declared == Scene.OTHER -> if (bottles.size == 1) Scene.BOTTLE else Scene.SHELF
            declared == Scene.BOTTLE && bottles.size > 1 -> Scene.SHELF
            declared == Scene.LIST && bottles.size == 1 -> Scene.LIST
            else -> declared
        }
        return SceneScan(scene, bottles, root.str("notes"))
    }

    private fun labelFrom(root: JsonObject, currencyFallback: String): ScannedLabel {
        val nowYear = java.time.LocalDate.now().year
        // Vintage must be a plausible bottling year: volume (750), ABV (125) and lot codes arrive
        // here when the model misreads small print, and saving them poisons the window and title.
        val rawVintage = root.intOrNull("vintage")
        val vintage = rawVintage?.takeIf { it in 1900..nowYear }
        val vintageDropped = rawVintage != null && vintage == null
        // ABV outside 5–22% is a misread volume, lot number or price — never a real table wine.
        val rawAlcohol = root.doubleOrNull("alcohol")
        val alcohol = rawAlcohol?.takeIf { it in 5.0..22.0 }
        val alcoholDropped = rawAlcohol != null && alcohol == null
        val rawPrice = root.doubleOrNull("price_estimate") ?: root.doubleOrNull("price")
        val price = rawPrice?.takeIf { it in 0.5..5000.0 }
        val glass = root.doubleOrNull("glass_price")?.takeIf { it in 0.5..5000.0 }
            // A "glass" that costs more than the bottle is the two numbers swapped or the same one
            // read twice; drop it rather than show a nonsense pair.
            ?.takeIf { bottle -> price == null || bottle < price }
        val wine = Wine(
            producer = root.str("producer"),
            name = root.str("name"),
            vintage = vintage,
            type = WineType.from(root.str("type")),
            country = root.str("country"),
            region = root.str("region"),
            appellation = root.str("appellation"),
            grapes = root.stringList("grapes"),
            alcohol = alcohol,
            barcode = root.str("barcode"),
            price = price,
            currency = root.str("currency").ifBlank { currencyFallback },
            drinkFrom = root.intOrNull("drink_from")?.takeIf { it in 1900..nowYear + 30 },
            drinkTo = root.intOrNull("drink_to")?.takeIf { it in 1900..nowYear + 30 },
            expertNotes = root.str("expert_notes"),
            flavorTags = root.stringList("flavor_tags"),
            fitScore = root.intOrNull("personal_fit")?.takeIf { it in 1..5 },
            fitReason = root.str("fit_reason"),
            source = "label-scan"
        )
        val uncertain = root.stringList("uncertain_fields")
            .map { it.trim().lowercase().takeIf { s -> s.isNotBlank() } }
            .filterNotNull()
            .distinct()
            .take(6)
        val flagged = buildList {
            addAll(uncertain)
            if (vintageDropped && !contains("vintage")) add("vintage")
            if (alcoholDropped && !contains("alcohol")) add("alcohol")
        }
        // A "high" confidence with no vintage and no ABV has not earned it: the two figures the
        // verdict and the window turn on are both missing.
        val declared = root.str("confidence").ifBlank { "low" }.lowercase()
        val confidence = when {
            declared != "high" -> declared.ifBlank { "low" }
            wine.producer.isBlank() || wine.name.isBlank() -> "medium"
            wine.vintage == null || wine.alcohol == null -> "medium"
            else -> "high"
        }
        val droppedNote = buildList {
            if (vintageDropped) add("vintage \"" + rawVintage + "\" is not a bottling year, ignored")
            if (alcoholDropped) add("alcohol \"" + rawAlcohol + "\" is outside 5–22%, ignored")
            if (rawPrice != null && price == null) add("price \"" + rawPrice + "\" looked wrong, ignored")
        }.joinToString("; ")
        val readNotes = listOf(root.str("read_notes"), droppedNote)
            .map { it.trim() }.filter { it.isNotBlank() }.joinToString(" · ")
        return ScannedLabel(
            wine = wine,
            fitScore = wine.fitScore,
            fitReason = root.str("fit_reason"),
            flavorTags = root.stringList("flavor_tags"),
            priceEstimate = price,
            confidence = confidence,
            readNotes = readNotes,
            labelText = root.str("label_text").ifBlank { root.str("key_lines") },
            wineFound = root.boolOrNull("wine_found") ?: true,
            valueVerdict = root.str("value_verdict").ifBlank { "unknown" },
            uncertainFields = flagged,
            nextTip = root.str("next_tip"),
            glassPrice = glass,
            listRank = root.intOrNull("list_rank")?.takeIf { it in 1..24 }
        )
    }
}