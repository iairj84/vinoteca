package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import java.util.Locale

/**
 * The four gates the user authored, in his order, and the one place they are evaluated.
 *
 * The generated profile used to render two of them — "G1 sweetness" and "G3 alcohol" — because the
 * ids had kept the numbering of the hand-built file it replaced while the rows between them were
 * dropped. The gap was the evidence: G2 Place and G4 Style words had been lost. Ids and titles are
 * fixed here so a later refactor cannot renumber them again, and [GateRegistry] is the only list
 * the profile table and the engine read: a gate cannot be missing from one and present in the other.
 *
 * The vocabulary is a keyword list, not a database. An appellation that is not listed is
 * [GateVerdict.UNKNOWN] — it never defaults to a pass, which is what keeps the gate honest on a
 * record that only says "Spain".
 */
enum class GateId(val title: String) {
    G1_ALCOHOL("G1 Alcohol"),
    G2_PLACE("G2 Place"),
    G3_DRY("G3 Dry"),
    G4_STYLE("G4 Style words")
}

/**
 * A hard gate has no allowance: a bottle that fails one is not offered. A soft gate may be broken
 * only with a stated reason, and it is where a user's allowance (spice with an off-dry wine) lives.
 */
enum class Severity { HARD, SOFT }

/**
 * PASS, CONDITIONAL, FAIL and UNKNOWN — four values, not a boolean.
 *
 * A [CONDITIONAL] is a pass that must print its basis ("14.2%, cool site, and there's food"). It is
 * never promoted silently to [PASS], because the number that needed the allowance is the thing the
 * answer has to say out loud. [UNKNOWN] is not a pass either: an unlisted ABV keeps a bottle out of
 * the ranked picks rather than letting it through.
 */
enum class GateVerdict {
    PASS,
    CONDITIONAL,
    FAIL,
    UNKNOWN;

    val isPass: Boolean get() = this == PASS || this == CONDITIONAL
}

/** The ticket's name for the same four values; one enum, so no surface can drift from another. */
typealias Outcome = GateVerdict

/**
 * **Yours** — the user said it, or rated a bottle. **Ours** — derived: scaled, thresholded, or
 * negated from a rejection. **Guess** — no input at all. **Unknown** — not enough to say.
 *
 * This is computed from where the value came from, never typed next to a value. "None, please" is
 * Yours; the 4.0 g/L threshold the app derives from it is Ours, and it says so on the line.
 */
enum class Confidence(val label: String) {
    YOURS("Yours"),
    OURS("Ours"),
    GUESS("Guess"),
    UNKNOWN("Unknown")
}

/** A number and whose number it is. */
data class Threshold(val value: Double, val unit: String, val confidence: Confidence)

/**
 * One gate as it stands for one profile: the id, how hard it is, the number behind it (if any), and
 * the words the rendered table prints. [source] and [confidence] are part of the spec so the table
 * cannot be built by hand beside the engine.
 */
data class GateSpec(
    val id: GateId,
    val severity: Severity,
    val threshold: Threshold?,
    /** The "Test" column. */
    val test: String,
    val source: String,
    val confidence: Confidence
) {
    val title: String get() = id.title

    /** True when the user's own answer put a number behind this gate. */
    val configured: Boolean get() = threshold != null
}

/**
 * The facts a gate can see, separated from [Wine] so the rules can be exercised without a record.
 *
 * [vineyard] and [hasCoolSite] are signals derived from the record (a named vineyard in the bottle's
 * name, a cool-site word in its place or notes). They are not a place database: a bottle with no
 * signal stays unknown.
 */
data class GateRow(
    val abv: Double? = null,
    val appellation: String? = null,
    val vineyard: String? = null,
    val priceUsd: Double? = null,
    val hasCoolSite: Boolean = false,
    val sugarGL: Double? = null,
    val description: String = ""
) {
    companion object {
        private val COOL_SITE = listOf(
            "cool site", "cool-site", "cool climate", "atlantic", "altitude", "mountain",
            "slate", "schist", "northern"
        )
        private val VINEYARD = listOf("vineyard", "single vineyard", "vyd", "clos")

        fun of(wine: Wine): GateRow {
            val text = buildString {
                append(wine.expertNotes).append(' ').append(wine.myNotes)
                wine.flavorTags.forEach { append(' ').append(it) }
            }
            val haystack = (text + " " + wine.appellation + " " + wine.region + " " +
                wine.name + " " + wine.producer).lowercase()
            val name = (wine.name + " " + wine.appellation).lowercase()
            return GateRow(
                abv = wine.alcohol,
                appellation = wine.appellation.trim().ifBlank { null },
                vineyard = VINEYARD.firstOrNull { it in name }?.let { wine.name.trim() },
                priceUsd = wine.price,
                hasCoolSite = COOL_SITE.any { it in haystack },
                sugarGL = sugarOf(wine.type, text.lowercase()),
                description = text
            )
        }

        /**
         * Residual sugar is almost never on a label, so this only answers when the record itself
         * says so: a dessert or fortified style, an "off-dry"/"demi-sec" note, or "bone dry".
         */
        private fun sugarOf(type: WineType, text: String): Double? = when {
            type == WineType.DESSERT || type == WineType.FORTIFIED -> 60.0
            listOf("off-dry", "off dry", "demi-sec", "demi sec", "feinherb",
                "touch of sweetness", "a little sweet").any { it in text } -> 9.0
            listOf("bone dry", "bone-dry").any { it in text } -> 1.0
            else -> null
        }
    }
}

/** The dish on the table, when there is one. Only heat changes a gate's answer today. */
data class Dish(val name: String, val spicy: Boolean = false)

object GateRegistry {

    /** The authored order. Everything renders and evaluates in this order. */
    val order: List<GateId> = GateId.entries

    /** The pass line on the currently rendered profile, and the ceiling above it. */
    const val ABV_PASS_LINE = 14.0
    const val ABV_CEILING = 14.5

    /**
     * Cool, specific appellations. Deliberately short: an unlisted place is unknown, never a pass,
     * and this is not a substitute for an appellation database.
     */
    private val SPECIFIC_PLACE = setOf(
        "penedès", "alt penedès", "priorat", "rias baixas", "getariako txakolina",
        "walla walla", "willamette", "howell mountain", "mayacamas range", "phinny hill"
    )

    /** The exact fail list from the authored rules. Restrained vanilla and toast are wanted. */
    val STYLE_FAIL = listOf("jammy", "plush", "baked", "sweet oak", "fruit bomb", "easy drinking")

    /** The exact pass list. No additions: "vanilla" and "toast" are not here. */
    val STYLE_PASS = listOf("herbal", "peppery", "savory", "bright", "cool-site", "cool site", "neutral oak")

    /**
     * The four specs for one profile, in authored order. A threshold is present only when the user
     * gave the number; a gate with no number is still a spec, rendered honestly as unset.
     */
    fun specs(
        alcoholCeiling: Double? = null,
        sugarCeiling: Double? = null,
        sugarSource: String? = null
    ): List<GateSpec> = listOf(
        GateSpec(
            id = GateId.G1_ALCOHOL,
            // Hard only where the user typed a ceiling and gave no allowance for it.
            severity = if (alcoholCeiling != null) Severity.HARD else Severity.SOFT,
            threshold = alcoholCeiling?.let { Threshold(it, "%", Confidence.YOURS) },
            test = if (alcoholCeiling != null) {
                "${fmt(alcoholCeiling)}% or under"
            } else {
                "No ceiling typed"
            },
            source = if (alcoholCeiling != null) "typed on the Taste screen" else "not answered",
            confidence = if (alcoholCeiling != null) Confidence.YOURS else Confidence.UNKNOWN
        ),
        GateSpec(
            id = GateId.G2_PLACE,
            severity = Severity.SOFT,
            threshold = null,
            test = "Cool, specific appellation or a named vineyard",
            source = "your earlier notes, the wording ours",
            confidence = Confidence.OURS
        ),
        GateSpec(
            id = GateId.G3_DRY,
            severity = Severity.SOFT,
            threshold = sugarCeiling?.let { Threshold(it, "g/L", Confidence.OURS) },
            test = if (sugarCeiling != null) {
                "Dry — under ${fmt(sugarCeiling)} g/L"
            } else {
                "No sweetness answer"
            },
            // "None, please" is Yours; the 4.0 g/L threshold is our number, and it prints as Ours.
            source = if (sugarCeiling != null) {
                listOfNotNull(sugarSource, "the threshold ours").joinToString(", ")
            } else {
                "not answered"
            },
            confidence = if (sugarCeiling != null) Confidence.OURS else Confidence.UNKNOWN
        ),
        GateSpec(
            id = GateId.G4_STYLE,
            severity = Severity.SOFT,
            threshold = null,
            test = "Herbal, peppery, savory, bright, cool-site or neutral oak — not jammy, " +
                "plush, baked, sweet oak, fruit bomb or easy drinking",
            source = "your earlier notes, the wording ours",
            confidence = Confidence.OURS
        )
    )

    /** Every gate, in authored order. */
    fun evaluate(row: GateRow, dish: Dish?, specs: List<GateSpec>): List<GateResult> =
        specs.map { spec ->
            when (spec.id) {
                GateId.G1_ALCOHOL -> alcohol(row, dish, spec)
                GateId.G2_PLACE -> place(row, spec)
                GateId.G3_DRY -> dry(row, dish, spec)
                GateId.G4_STYLE -> style(row, spec)
            }
        }

    /**
     * FAIL removes a candidate. UNKNOWN keeps it out of the ranked picks but reports it, so an
     * unknown-ABV bottle loses to one whose number is on record instead of being quietly promoted.
     */
    fun isOrderable(verdicts: List<GateResult>): Boolean =
        verdicts.none { it.verdict == GateVerdict.FAIL || it.verdict == GateVerdict.UNKNOWN }

    private fun alcohol(row: GateRow, dish: Dish?, spec: GateSpec): GateResult {
        val ceiling = spec.threshold?.value
            ?: return GateResult(spec.title, GateVerdict.UNKNOWN, "No alcohol ceiling was typed", spec.configured)
        // Compare on the printed one-decimal value, never on the raw float.
        val abv = row.abv?.let { round1(it) }
            ?: return GateResult(
                spec.title, GateVerdict.UNKNOWN, "ABV unknown — check the label", spec.configured
            )
        val printed = fmt(abv)
        return when {
            abv <= ceiling -> GateResult(
                spec.title, GateVerdict.PASS, "$printed% — inside your ${fmt(ceiling)}% ceiling", spec.configured
            )
            abv > ABV_CEILING -> GateResult(
                spec.title,
                GateVerdict.FAIL,
                "$printed% is over the 14.5 ceiling, above your ${fmt(ceiling)}%",
                spec.configured
            )
            row.hasCoolSite || dish != null -> GateResult(
                spec.title,
                GateVerdict.CONDITIONAL,
                "$printed% — above your ${fmt(ceiling)}%, allowed for " +
                    if (row.hasCoolSite) "the cool site" else "the food",
                spec.configured
            )
            else -> GateResult(
                spec.title,
                GateVerdict.FAIL,
                "$printed% with no cool site and no food",
                spec.configured
            )
        }
    }

    private fun place(row: GateRow, spec: GateSpec): GateResult {
        if (!row.vineyard.isNullOrBlank()) {
            return GateResult(spec.title, GateVerdict.PASS, "${row.vineyard} is a named vineyard", spec.configured)
        }
        val appellation = row.appellation?.trim()?.lowercase()
            ?: return GateResult(
                spec.title, GateVerdict.UNKNOWN, "No place on the record — check the label", spec.configured
            )
        if (appellation in SPECIFIC_PLACE) {
            return GateResult(spec.title, GateVerdict.PASS, "$appellation is specific", spec.configured)
        }
        val price = row.priceUsd
        return if (price != null && price > 10.0) {
            GateResult(
                spec.title,
                GateVerdict.FAIL,
                "${row.appellation} is a broad appellation with no vineyard named, and it is over \$10",
                spec.configured
            )
        } else {
            GateResult(
                spec.title, GateVerdict.UNKNOWN, "$appellation is not a place I can check", spec.configured
            )
        }
    }

    private fun dry(row: GateRow, dish: Dish?, spec: GateSpec): GateResult {
        val ceiling = spec.threshold?.value
            ?: return GateResult(spec.title, GateVerdict.UNKNOWN, "No sweetness answer", spec.configured)
        val sugar = row.sugarGL?.let { round1(it) }
            ?: return GateResult(
                spec.title, GateVerdict.UNKNOWN, "Residual sugar is not on the label", spec.configured
            )
        val printed = fmt(sugar)
        return when {
            sugar <= ceiling -> GateResult(
                spec.title, GateVerdict.PASS, "$printed g/L — dry, under your ${fmt(ceiling)} g/L", spec.configured
            )
            // The off-dry Riesling the chicken-tikka example recommends: a pass that has to say why.
            dish?.spicy == true -> GateResult(
                spec.title,
                GateVerdict.CONDITIONAL,
                "$printed g/L — above your ${fmt(ceiling)} g/L, allowed with the spice",
                spec.configured
            )
            else -> GateResult(
                spec.title,
                GateVerdict.FAIL,
                "$printed g/L — over your ${fmt(ceiling)} g/L with nothing spicy to carry it",
                spec.configured
            )
        }
    }

    private fun style(row: GateRow, spec: GateSpec): GateResult {
        val text = row.description.lowercase()
        if (STYLE_FAIL.any { it in text }) {
            val word = STYLE_FAIL.first { it in text }
            return GateResult(spec.title, GateVerdict.FAIL, "\"$word\" is on the avoid list", spec.configured)
        }
        if (STYLE_PASS.any { it in text }) {
            val word = STYLE_PASS.first { it in text }
            return GateResult(spec.title, GateVerdict.PASS, "\"$word\" is the kind of word wanted", spec.configured)
        }
        return GateResult(
            spec.title, GateVerdict.UNKNOWN, "Nothing in the description says how it is styled", spec.configured
        )
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
}
