package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType

enum class GateVerdict { PASS, FAIL, UNKNOWN }

data class GateResult(val gate: String, val verdict: GateVerdict, val detail: String) {
    val flag: String
        get() = when (verdict) {
            GateVerdict.PASS -> "pass"
            GateVerdict.FAIL -> "fails"
            GateVerdict.UNKNOWN -> "unknown"
        }
}

enum class OakTolerance(val label: String) {
    NEUTRAL("restrained"),
    LIGHT("a little is fine"),
    ANY("bring it")
}

/**
 * The thresholds the app itself checks against a bottle record.
 *
 * Two rules matter here. A threshold exists only when the user named it — nothing is filled in to make
 * the gate look complete. And a missing fact is [GateVerdict.UNKNOWN], never [GateVerdict.PASS]: an
 * unlisted ABV cannot satisfy an alcohol ceiling, and saying so out loud is the whole point. The model
 * is told these results; it is never asked to compute them.
 */
data class PalateRules(
    /** Grams per litre. 4.0 for "none, please", 8.0 for "a touch is fine". */
    val maxSugar: Double? = null,
    val oak: OakTolerance? = null,
    /** Percent alcohol by volume. Only ever a number the user gave: no chip becomes a ceiling. */
    val maxAlcohol: Double? = null
) {
    val isEmpty: Boolean get() = maxSugar == null && oak == null && maxAlcohol == null

    fun checks(wine: Wine): List<GateResult> = buildList {
        if (maxSugar != null) add(sugarCheck(wine))
        if (oak != null) add(oakCheck(wine))
        if (maxAlcohol != null) add(alcoholCheck(wine))
    }

    fun verdictOf(gate: String, wine: Wine): GateVerdict =
        checks(wine).firstOrNull { it.gate == gate }?.verdict ?: GateVerdict.UNKNOWN

    /** True when the bottle breaks one of the user's rules. An unknown answer is never a failure. */
    fun fails(wine: Wine): Boolean = checks(wine).any { it.verdict == GateVerdict.FAIL }

    /**
     * A one-line notice when a bottle the user loves breaks a ceiling the user set themselves.
     *
     * Worth naming rather than hiding: a rule that fails a 5★ bottle is more likely to be a rule
     * that arrived without the user's consent than a bottle the app should talk them out of. Only a
     * numeric ceiling counts — oak is a style note, and a tag that mentions it is not a measurement.
     */
    fun lovedConflict(wines: List<Wine>): String? {
        val broken = wines
            .filter { it.favorite || (it.rating ?: 0) >= 4 }
            .firstNotNullOfOrNull { wine ->
                checks(wine)
                    .firstOrNull { it.verdict == GateVerdict.FAIL && it.gate != GATE_OAK }
                    ?.let { wine to it }
            } ?: return null
        return "${broken.first.title} is one you loved, but ${broken.second.detail}. " +
            "Your rating and your rule disagree."
    }

    /**
     * One line for the cellar digest the chat prompt carries: the failures, plus an honest note when
     * the ceiling exists but the bottle cannot be checked.
     */
    fun digestNote(wine: Wine): String {
        val notes = mutableListOf<String>()
        checks(wine)
            .filter { it.verdict == GateVerdict.FAIL }
            .forEach { notes += "${it.gate} ${it.flag}: ${it.detail}" }
        if (maxAlcohol != null && wine.alcohol == null) {
            notes += "alcohol unknown: the record has no ABV, so my ${fmt(maxAlcohol)}% ceiling cannot be checked"
        }
        return notes.joinToString("; ")
    }

    private fun sugarCheck(wine: Wine): GateResult = when (wine.type) {
        WineType.DESSERT -> GateResult(
            GATE_SUGAR,
            GateVerdict.FAIL,
            "dessert style, and my ceiling is ${fmt(maxSugar)} g/L"
        )
        WineType.FORTIFIED -> GateResult(
            GATE_SUGAR,
            GateVerdict.FAIL,
            "fortified, so sweet by style and over my ${fmt(maxSugar)} g/L ceiling"
        )
        else -> GateResult(
            GATE_SUGAR,
            GateVerdict.UNKNOWN,
            "sugar is not on the label, so it cannot be checked from the record"
        )
    }

    /** Tasting tags that name oak, for the gate and for the match line. */
    fun oakMentions(wine: Wine): List<String> = wine.flavorTags
        .map { it.lowercase() }
        .filter { tag -> OAK_WORDS.any { tag.contains(it) } }

    private fun oakCheck(wine: Wine): GateResult {
        val hits = oakMentions(wine)
        return if (oak == OakTolerance.NEUTRAL && hits.isNotEmpty()) {
            GateResult(GATE_OAK, GateVerdict.FAIL, "tasting tags mention ${hits.joinToString(", ")}")
        } else {
            // Oak is a style, not a printed number: a record can fail the test but can never pass it.
            GateResult(GATE_OAK, GateVerdict.UNKNOWN, "oak is not on the label; judge it from the notes")
        }
    }

    private fun alcoholCheck(wine: Wine): GateResult {
        val ceiling = maxAlcohol
            ?: return GateResult(GATE_ALCOHOL, GateVerdict.UNKNOWN, "no ceiling set")
        val abv = wine.alcohol
            ?: return GateResult(GATE_ALCOHOL, GateVerdict.UNKNOWN, "the record has no ABV")
        return if (abv > ceiling + 0.05) {
            GateResult(GATE_ALCOHOL, GateVerdict.FAIL, "${fmt(abv)}% is over my ${fmt(ceiling)}% ceiling")
        } else {
            GateResult(GATE_ALCOHOL, GateVerdict.PASS, "${fmt(abv)}% is under my ${fmt(ceiling)}% ceiling")
        }
    }

    companion object {
        const val GATE_SUGAR = "G1 sweetness"
        const val GATE_OAK = "G2 oak"
        const val GATE_ALCOHOL = "G3 alcohol"

        private val OAK_WORDS = listOf("oak", "vanilla", "toast", "buttery", "butter", "cedar", "coconut")

        private fun fmt(value: Double?): String =
            if (value == null) "—" else String.format(java.util.Locale.US, "%.1f", value)

        fun from(profile: PalateProfile): PalateRules {
            val avoids = profile.choices(QuickStart.Q_AVOID)
            val sugar = when (profile.choice(QuickStart.Q_SWEET)) {
                QuickStart.SWEET_NONE -> 4.0
                QuickStart.SWEET_TOUCH -> 8.0
                else -> null
            }
            val oak = when {
                QuickStart.AVOID_OAK in avoids -> OakTolerance.NEUTRAL
                else -> null
            }
            // "Avoid strong" is a chip, not a number. Turning it into a 13.5% ceiling invented a
            // rule the assistant then defended, and it contradicted the 5★ Zinfandel in the same
            // cellar. The preference still reaches the model, worded as a preference; a numeric
            // ceiling exists only when the user gives one.
            return PalateRules(maxSugar = sugar, oak = oak)
        }
    }
}