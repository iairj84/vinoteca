package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
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
    val notes: String,
    /** The validated advice answer, when one came back. Null means a ranking is the fallback. */
    val advice: ListAdvice? = null
)

/** One bottle the advice points at, by its position on the page. */
data class AdvicePick(
    /** 0-based index into [SceneScan.bottles]: the card is built from that bottle, never the text. */
    val index: Int,
    val headline: String,
    val why: String,
    /**
     * Shelf advice only: good, fair or poor at the printed price. Null on a list (a menu price is
     * a markup, not a deal) and on any bottle whose price was not printed, because a value claim
     * about a price nobody read is not evidence.
     */
    val value: String? = null,
    /** The clause after the value word, when the answer gave one. */
    val valueNote: String = ""
)

/** The advice answer, with every line that failed a check already removed. */
data class ListAdvice(
    val picks: List<AdvicePick>,
    val glass: AdvicePick? = null,
    val skips: List<AdvicePick> = emptyList(),
    val note: String = "",
    /** Why a line was thrown away. For the debug panel only — never shown as advice. */
    val dropped: List<String> = emptyList()
)

object ScanParsing {

    /**
     * Words that only turn up when a model echoes the schema back instead of reading the photo:
     * a card titled "name · Fields: producer" is a template, not a wine. Such a value is treated
     * as blank, and a line whose producer and name are both template words is dropped entirely —
     * no card at all beats a card about nothing.
     */
    private val SCHEMA_WORDS = setOf(
        "name", "producer", "vintage", "region", "country", "appellation", "grape", "grapes",
        "type", "alcohol", "abv", "price", "bottle price", "glass price", "bottle", "glass",
        "confidence", "rank", "why", "reason", "string", "number", "null", "true", "false",
        "array", "object", "fields", "scene", "notes", "currency", "value", "field"
    )

    private fun placeholder(value: String): Boolean {
        val clean = value.trim().trim('"', '\'').lowercase().trimEnd('.', ':')
        if (clean.isEmpty()) return false
        if (clean in SCHEMA_WORDS) return true
        if (clean.startsWith("fields:") || clean.startsWith("field:")) return true
        // A field that still holds a separator or a <slot> is the template, never a label.
        if (clean.contains('|') || clean.contains("{") || clean.contains("}")) return true
        return clean.startsWith("<") && clean.endsWith(">")
    }

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
        val producer = text(0).takeUnless { placeholder(it) }.orEmpty()
        val name = text(1).takeUnless { placeholder(it) }.orEmpty()
        if (producer.isBlank() && name.isBlank()) return null
        val vintage = text(2).replace(Regex("[^0-9]"), "").toIntOrNull()?.takeIf { it in 1900..year }
        val grapes = text(4).split(';', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "-" && !placeholder(it) }
        val bottle = number(6)?.takeIf { it < 5000.0 }
        // A "glass" that costs more than the bottle is the two figures swapped or one read twice.
        val glass = number(7)?.takeIf { it < 5000.0 }?.takeIf { bottle == null || it < bottle }
        val declared = text(8).lowercase()
        val rank = text(9).replace(Regex("[^0-9]"), "").toIntOrNull()?.takeIf { it in 1..24 }
        // What the wine is, then why it sits where it does. A twelve-field answer carries both; an
        // eleven-field one is the older shape and its tenth field is the reason alone.
        val described = parts.size >= 12
        val what = if (described) text(10).take(120) else ""
        val why = text(if (described) 11 else 10).take(160)
        val confidence = when {
            declared.startsWith("hi") -> "high"
            declared.startsWith("lo") -> "low"
            else -> "medium"
        }
        val wine = Wine(
            producer = producer,
            name = name,
            vintage = vintage,
            type = WineType.from(text(5).takeUnless { placeholder(it) }.orEmpty()),
            region = text(3).takeUnless { placeholder(it) }.orEmpty(),
            grapes = grapes,
            price = bottle,
            currency = currencyFallback,
            expertNotes = what,
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


    /**
     * The advice answer, parsed and checked.
     *
     * A model writes prose about a page it cannot see, so every line is a claim to verify against
     * the bottle it names. Five checks, and a line that fails one is dropped rather than repaired:
     * the line number must exist on the page; a GLASS pick must be a wine the menu actually sells
     * by the glass; a line may not call a wine red when the list puts it under ROSADO, or the
     * reverse; one bottle is picked once; and no pick left means null, so the caller can fall back.
     *
     * Tags are matched in any case with leading bullets and stray whitespace forgiven, multi-line
     * WHY text is joined until the next tag, and a line that is not a tag is ignored.
     */
    fun parseAdvice(raw: String, read: SceneScan): ListAdvice? {
        val drafts = adviceDrafts(raw)
        val dropped = mutableListOf<String>()
        val used = mutableSetOf<Int>()
        val count = read.bottles.size

        fun resolve(draft: AdviceDraft): Int? {
            val index = draft.index
            if (index == null || index !in 0 until count) {
                dropped += "${draft.kind} ${draft.number ?: "?"} — no such line on the page"
                return null
            }
            return index
        }

        // A wine's colour is the section it is listed under, whatever the grape usually makes.
        fun colourAgrees(draft: AdviceDraft, index: Int): Boolean {
            val type = read.bottles[index].wine.type
            val expected = colourOf(type) ?: return true
            val claimed = colourClaims(draft.headline + " " + draft.why)
            if (claimed.isEmpty() || expected in claimed) return true
            dropped += "${draft.kind} ${index + 1} — described as ${claimed.joinToString(" / ")}, " +
                "listed as ${type.label.lowercase()}"
            return false
        }

        val picks = mutableListOf<AdvicePick>()
        drafts.filter { it.kind == "PICK" }.forEach { draft ->
            val index = resolve(draft) ?: return@forEach
            if (draft.headline.isBlank()) {
                dropped += "PICK ${index + 1} — no headline"
                return@forEach
            }
            if (!used.add(index)) {
                dropped += "PICK ${index + 1} — already picked"
                return@forEach
            }
            if (!colourAgrees(draft, index)) return@forEach
            if (picks.size < 3) {
                picks += pickOf(draft, index, read)
            } else {
                dropped += "PICK ${index + 1} — more than three picks"
            }
        }
        if (picks.isEmpty()) return null

        val glass = drafts.firstOrNull { it.kind == "GLASS" }?.let { draft ->
            val index = resolve(draft) ?: return@let null
            if (read.bottles[index].glassPrice == null) {
                dropped += "GLASS ${index + 1} — no glass price is printed"
                return@let null
            }
            if (!colourAgrees(draft, index)) return@let null
            pickOf(draft, index, read)
        }

        val skips = drafts.filter { it.kind == "SKIP" }
            .mapNotNull { draft ->
                val index = resolve(draft) ?: return@mapNotNull null
                if (!colourAgrees(draft, index)) return@mapNotNull null
                pickOf(draft, index, read)
            }
            .distinctBy { it.index }
            .take(4)

        val note = drafts.lastOrNull { it.kind == "NOTE" }?.why?.toString()?.trim().orEmpty()
        return ListAdvice(picks, glass, skips, note, dropped)
    }

    /** One tag line and the text that follows it, before any validation. */
    private class AdviceDraft(
        val kind: String,
        val number: String?,
        val index: Int?,
        val headline: String
    ) {
        val why = StringBuilder()
        var open = false
        /** The shelf's VALUE line, kept apart from the WHY so the two are not run together. */
        val value = StringBuilder()
    }

    private fun adviceDrafts(raw: String): List<AdviceDraft> {
        val out = mutableListOf<AdviceDraft>()
        var last: AdviceDraft? = null
        raw.lines().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
            if (line.isEmpty()) return@forEach
            val upper = line.uppercase()
            when {
                upper.startsWith("PICK") || upper.startsWith("GLASS") || upper.startsWith("SKIP") -> {
                    val kind = when {
                        upper.startsWith("PICK") -> "PICK"
                        upper.startsWith("GLASS") -> "GLASS"
                        else -> "SKIP"
                    }
                    val parts = line.split('|', limit = 3).map { it.trim() }
                    val number = parts.getOrNull(1).orEmpty()
                    val draft = AdviceDraft(
                        kind = kind,
                        number = number.ifBlank { null },
                        index = number.replace(Regex("[^0-9]"), "").toIntOrNull()?.minus(1),
                        headline = parts.getOrNull(2).orEmpty().trim()
                    )
                    out += draft
                    last = draft
                }
                upper.startsWith("WHY") && line.contains(':') -> {
                    val draft = last
                    if (draft != null && draft.kind != "NOTE") {
                        draft.why.append(line.substringAfter(':').trim())
                        draft.open = true
                    }
                }
                upper.startsWith("VALUE") && line.contains(':') -> {
                    // A shop answer's price verdict, attached to the bottle it follows. It does not
                    // open the WHY, or the value clause would be glued onto the reason text.
                    val draft = last
                    if (draft != null && draft.kind != "NOTE") {
                        draft.value.append(line.substringAfter(':').trim())
                        draft.open = false
                    }
                }
                upper.startsWith("NOTE") && line.contains(':') -> {
                    val draft = AdviceDraft("NOTE", null, null, "")
                    draft.why.append(line.substringAfter(':').trim())
                    draft.open = true
                    out += draft
                    last = draft
                }
                else -> {
                    // A WHY or NOTE that wrapped across lines: keep it until the next tag.
                    val draft = last
                    if (draft != null && draft.open) draft.why.append(' ').append(line)
                }
            }
        }
        return out
    }

    /**
     * One validated line as the card needs it.
     *
     * The shelf's value word is kept only when the price it is about was printed: a claim that a
     * bottle is good value next to no price is the model inventing the number it compared.
     */
    private fun pickOf(draft: AdviceDraft, index: Int, read: SceneScan): AdvicePick {
        // A menu price is a markup, not a deal, so a list answer keeps no value whatever the model
        // volunteers; only a shop's printed shelf price can be called good or poor.
        val price = if (read.scene == Scene.SHELF) read.bottles[index].wine.price else null
        val (value, note) = valueOf(draft, price)
        return AdvicePick(index, draft.headline, draft.why.toString().trim(), value, note)
    }

    /** "good — a bargain at $24" becomes "good" and "a bargain at $24". */
    private fun valueOf(draft: AdviceDraft, price: Double?): Pair<String?, String> {
        if (price == null) return null to ""
        val text = draft.value.toString().trim()
        if (text.isEmpty()) return null to ""
        val word = Regex("(?i)\\b(good|fair|poor)\\b").find(text)
            ?.groupValues?.get(1)?.lowercase() ?: return null to ""
        val note = text.substring(text.lowercase().indexOf(word) + word.length)
            .trim()
            .trimStart('—', '-', ':', '|', ',', ' ')
            .trim()
        return word to note
    }

    /** The colour the list's section says a wine is, or null when the section says nothing. */
    private fun colourOf(type: WineType): String? = when (type) {
        WineType.RED -> "red"
        WineType.WHITE -> "white"
        WineType.ROSE -> "rosé"
        WineType.SPARKLING -> "sparkling"
        WineType.ORANGE -> "orange"
        WineType.DESSERT, WineType.FORTIFIED -> null
    }

    private val COLOUR_WORDS = mapOf(
        "red" to "red",
        "white" to "white",
        "rose" to "rosé",
        "rosé" to "rosé",
        "rosado" to "rosé",
        "sparkling" to "sparkling",
        "bubbles" to "sparkling",
        "orange" to "orange"
    )

    /**
     * "Red fruit", "white pepper" and "orange peel" are tasting notes, not statements about the
     * wine's colour, so the word after a colour is what decides whether it is a claim.
     */
    private val FLAVOUR_AFTER_COLOUR = setOf(
        "fruit", "fruits", "fruited", "cherry", "cherries", "plum", "plums", "berry", "berries",
        "currant", "currants", "apple", "apples", "grape", "grapes", "meat", "meats", "sauce",
        "onion", "onions", "pepper", "peppers", "flowers", "floral", "peel", "citrus", "liquorice"
    )

    /** "Drinks like a white", "lighter than a red": a comparison, not a claim about this wine. */
    private val SIMILE_BEFORE_COLOUR = setOf("like", "than", "as", "unlike")

    private fun colourClaims(text: String): Set<String> {
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val out = mutableSetOf<String>()
        words.forEachIndexed { index, word ->
            val colour = COLOUR_WORDS[word] ?: return@forEachIndexed
            if (words.getOrNull(index + 1).orEmpty() in FLAVOUR_AFTER_COLOUR) return@forEachIndexed
            // "like a white" puts one word between the simile and the colour; two back covers it.
            if (words.getOrNull(index - 1).orEmpty() in SIMILE_BEFORE_COLOUR) return@forEachIndexed
            if (words.getOrNull(index - 2).orEmpty() in SIMILE_BEFORE_COLOUR) return@forEachIndexed
            out += colour
        }
        return out
    }

    /**
     * The validated picks as the page's ranks, for the cards.
     *
     * Only the picks are placed — two or three explained bottles, which is what the answer is for —
     * and the rest of the page stays unranked until someone asks about it.
     */
    fun applyAdvice(read: SceneScan, advice: ListAdvice): SceneScan {
        val rankOf = advice.picks.mapIndexed { position, pick -> pick.index to position + 1 }.toMap()
        return read.copy(
            bottles = read.bottles.mapIndexed { index, label ->
                rankOf[index]?.let { label.copy(listRank = it) } ?: label
            },
            advice = advice
        )
    }

    fun applyAdvice(read: SceneScan, raw: String): SceneScan? =
        parseAdvice(raw, read)?.let { applyAdvice(read, it) }

    /**
     * The text-only judgement on a list the phone read: "index | rank | fit | what | why" lines
     * matched to the wines by number. A line that does not parse, a rank outside the page, or a
     * template echo for the reason is dropped. Null when fewer than two lines survived, because
     * one placed wine is not a ranking — the caller then reads the page from the photo instead of
     * leaving a price order on screen.
     */
    fun applyJudgement(read: SceneScan, raw: String): SceneScan? {
        val count = read.bottles.size
        val placed = raw.lines()
            .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                if (parts.size < 4) return@mapNotNull null
                val index = parts[0].replace(Regex("[^0-9]"), "").toIntOrNull()?.minus(1)
                    ?.takeIf { it in 0 until count } ?: return@mapNotNull null
                val rank = parts[1].replace(Regex("[^0-9]"), "").toIntOrNull()
                    ?.takeIf { it in 1..count } ?: return@mapNotNull null
                val fit = parts[2].replace(Regex("[^0-9]"), "").toIntOrNull()
                    ?.takeIf { it in 1..5 }
                // Five fields carry "what it is" as well as the reason; a four-field answer is the
                // older shape and still parses, with no description.
                val what = if (parts.size >= 5) parts[3].take(120).trim() else ""
                // A reason that itself contains a pipe is kept whole rather than cut at its first
                // clause: only the leading fields are positional.
                val why = (if (parts.size >= 5) parts.drop(4).joinToString(" | ") else parts[3])
                    .take(160).trim()
                if (why.isBlank() || placeholder(why)) return@mapNotNull null
                Judged(index, rank, fit, what.takeUnless { placeholder(it) }.orEmpty(), why)
            }
            .distinctBy { it.index }
        if (placed.size < 2) return null
        // Data repair for the Nov 25 misbinding: the judgement's `what` sometimes echoes a
        // neighbouring wine's title ("2021 'DAMA' ... · BODEGAS NAVERAN" attached to a Miquel
        // Pons row). A `what` that names a different wine on this page is a merged row, not a
        // description, so it is dropped here rather than stored on the record. The renderer
        // guard below is the backstop; this keeps the stored row clean.
        fun namesProducer(text: String, producer: String): Boolean {
            val clean = producer.trim()
            if (clean.length < 3) return false
            return Regex("\\b${Regex.escape(clean)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        fun prefixSibling(a: String, b: String): Boolean {
            val x = a.lowercase()
            val y = b.lowercase()
            return x.startsWith(y) || y.startsWith(x)
        }
        val pageProducers = read.bottles.map { it.wine.producer }
        val pageCuvees = read.bottles.map { it.wine.name }
        fun cuveeWords(s: String): List<String> =
            s.uppercase().split(Regex("[^À-ÞA-Z]+")).filter { it.length >= 3 }
        // Two-word phrases: single generic words like CAVA appear in legitimate
        // descriptions, so only a shared phrase means the `what` belongs elsewhere.
        fun cuveePhrases(other: String, own: String): List<String> {
            fun bigrams(words: List<String>): Set<String> =
                words.zipWithNext { a, b -> a + " " + b }.toSet()
            return (bigrams(cuveeWords(other)) - bigrams(cuveeWords(own))).toList()
        }
        fun foreignWhat(index: Int, what: String): Boolean {
            if (what.isBlank()) return false
            return pageProducers.indices.any { j ->
                if (j == index) return@any false
                // Different producer named outright (the Nov 25 shape).
                if (pageProducers[j].isNotBlank() &&
                    !prefixSibling(pageProducers[j], pageProducers[index]) &&
                    namesProducer(what, pageProducers[j])) return@any true
                // Same-producer blind spot: the sibling's cuvee is the tell when the
                // producer matches (two Castell d'Or rows). A two-word phrase of the
                // sibling's cuvee inside `what` means it belongs to the sibling.
                cuveePhrases(pageCuvees[j], pageCuvees[index]).any { phrase ->
                    what.uppercase().replace(Regex("[^À-ÞA-Z]+"), " ").contains(phrase) && phrase.length >= 8
                }
            }
        }
        val byIndex = placed.associateBy { it.index }
        val bottles = read.bottles.mapIndexed { index, label ->
            val hit = byIndex[index] ?: return@mapIndexed label
            val what = if (foreignWhat(index, hit.what)) "" else hit.what
            label.copy(
                fitScore = hit.fit,
                fitReason = hit.why,
                listRank = hit.rank,
                // "what it is" belongs on the record as well as the card: it is the line that
                // tells someone who has never heard of the wine what they are looking at, and it
                // is worth keeping when the bottle is added to the cellar.
                wine = if (what.isBlank()) {
                    label.wine
                } else {
                    label.wine.copy(expertNotes = what)
                }
            )
        }
        return read.copy(bottles = bottles)
    }

    /** One judged line of a list, matched back to its wine by number. */
    private data class Judged(
        val index: Int,
        val rank: Int,
        val fit: Int?,
        val what: String,
        val why: String
    )

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
        fun field(key: String): String =
            root.str(key).takeUnless { placeholder(it) }.orEmpty()
        val wine = Wine(
            producer = field("producer"),
            name = field("name"),
            vintage = vintage,
            type = WineType.from(field("type")),
            country = field("country"),
            region = field("region"),
            appellation = field("appellation"),
            grapes = root.stringList("grapes").filterNot { placeholder(it) },
            alcohol = alcohol,
            barcode = root.str("barcode"),
            price = price,
            currency = root.str("currency").ifBlank { currencyFallback },
            drinkFrom = root.intOrNull("drink_from")?.takeIf { it in 1900..nowYear + 30 },
            drinkTo = root.intOrNull("drink_to")?.takeIf { it in 1900..nowYear + 30 },
            expertNotes = field("expert_notes"),
            flavorTags = root.stringList("flavor_tags").filterNot { placeholder(it) },
            fitScore = root.intOrNull("personal_fit")?.takeIf { it in 1..5 },
            fitReason = field("fit_reason"),
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