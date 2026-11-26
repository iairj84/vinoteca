package com.wineverdict.app.data

import com.wineverdict.app.model.Wine

data class GateResult(
    val gate: String,
    val verdict: GateVerdict,
    val detail: String,
    /** True when the profile put a number behind this gate; gates without one are not "set". */
    val configured: Boolean = true
) {
    val flag: String
        get() = when (verdict) {
            GateVerdict.PASS -> "pass"
            GateVerdict.CONDITIONAL -> "conditional"
            GateVerdict.FAIL -> "fails"
            GateVerdict.UNKNOWN -> "unknown"
        }
}

/**
 * The thresholds the app itself checks against a bottle record.
 *
 * Two rules matter here. A threshold exists only when the user named it — nothing is filled in to make
 * the gate look complete: "avoid strong" and "avoid heavy oak" are preferences in the brief now, not
 * numbers or tests the app invents. And a missing fact is [GateVerdict.UNKNOWN], never
 * [GateVerdict.PASS]: an unlisted ABV cannot satisfy an alcohol ceiling, and saying so out loud is the
 * whole point. The model is told these results; it is never asked to compute them.
 *
 * The evaluation itself lives in [GateRegistry], which runs all four authored gates in order. This
 * type only carries the two numbers a profile can set, so it stays the single source for them.
 */
data class PalateRules(
    /** Grams per litre. 4.0 for "none, please", 8.0 for "a touch is fine". */
    val maxSugar: Double? = null,
    /** Percent alcohol by volume. Only ever a number the user typed: no chip becomes a ceiling. */
    val maxAlcohol: Double? = null
) {
    val isEmpty: Boolean get() = maxSugar == null && maxAlcohol == null

    /** The four specs for this profile, in authored order, for the profile table and the engine. */
    fun specs(): List<GateSpec> = GateRegistry.specs(maxAlcohol, maxSugar)

    /**
     * All four gates, evaluated in the authored order, before anything is scored.
     *
     * [scope] decides what a hard gate means: a limit on the shelf, or advice about a bottle that is
     * already home. Purchase is the default because that is the strict reading, and silence should
     * never be the thing that relaxes a rule.
     */
    fun verdicts(
        wine: Wine,
        dish: Dish? = null,
        scope: GateScope = GateScope.PURCHASE
    ): List<GateResult> = GateRegistry.evaluate(GateRow.of(wine), dish, specs(), scope)

    /**
     * The projection onto the gates that carry a number the user set. A cellar page shows these as
     * the rules being enforced; a gate with no threshold is not a rule yet.
     */
    fun checks(wine: Wine, scope: GateScope = GateScope.PURCHASE): List<GateResult> =
        verdicts(wine, scope = scope).filter { it.configured }

    fun verdictOf(gate: String, wine: Wine): GateVerdict =
        verdicts(wine).firstOrNull { it.gate == gate }?.verdict ?: GateVerdict.UNKNOWN

    /**
     * True when the bottle breaks one of the user's rules. An unknown answer is never a failure, and
     * a conditional pass is a pass that printed its basis.
     */
    fun fails(wine: Wine): Boolean = verdicts(wine).any { it.verdict == GateVerdict.FAIL }

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
                    .firstOrNull { it.verdict == GateVerdict.FAIL }
                    ?.let { wine to it }
            } ?: return null
        return "${broken.first.title} is one you loved, but ${broken.second.detail}. " +
            "Your rating and your rule disagree."
    }

    /**
     * One line for the cellar digest the chat prompt carries: the failures, plus an honest note when
     * the ceiling exists but the bottle cannot be checked.
     */
    fun digestNote(
        wine: Wine,
        dish: Dish? = null,
        scope: GateScope = GateScope.PURCHASE
    ): String {
        val notes = mutableListOf<String>()
        // A conditional pass is reported too: the model has to say the number and why, so the one
        // line it reads must carry both. Without a dish this is the same failure list as before.
        verdicts(wine, dish, scope)
            .filter { it.configured && it.verdict in setOf(GateVerdict.FAIL, GateVerdict.CONDITIONAL) }
            .forEach { notes += "${it.gate} ${it.flag}: ${it.detail}" }
        if (maxAlcohol != null && wine.alcohol == null) {
            notes += "alcohol unknown: the record has no ABV, so my ${fmt(maxAlcohol)}% ceiling cannot be checked"
        }
        return notes.joinToString("; ")
    }

    companion object {
        val GATE_SUGAR = GateId.G3_DRY.title
        val GATE_ALCOHOL = GateId.G1_ALCOHOL.title

        private fun fmt(value: Double?): String =
            if (value == null) "—" else String.format(java.util.Locale.US, "%.1f", value)

        fun from(profile: PalateProfile): PalateRules {
            val sugar = when (profile.choice(QuickStart.Q_SWEET)) {
                QuickStart.SWEET_NONE -> 4.0
                QuickStart.SWEET_TOUCH -> 8.0
                else -> null
            }
            // Both "avoid" chips are preferences now, carried to the model by the brief. Turning
            // "avoid strong" into a 13.5% ceiling invented a rule the assistant then defended, and
            // it contradicted the 5★ Zinfandel in the same cellar; an oak gate did the same one
            // layer down. A ceiling exists only when the user types a number.
            return PalateRules(
                maxSugar = sugar,
                maxAlcohol = profile.alcoholCeiling?.takeIf { it in PalateProfile.ALCOHOL_RANGE }
            )
        }
    }
}
