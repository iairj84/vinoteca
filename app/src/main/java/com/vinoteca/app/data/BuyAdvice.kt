package com.vinoteca.app.data

import com.vinoteca.app.model.DrinkStatus
import com.vinoteca.app.model.Wine
import com.vinoteca.app.model.WineType
import com.vinoteca.app.model.drinkStatus
import com.vinoteca.app.model.formatMoney

/**
 * What to do with a bottle that is not in the cellar yet.
 *
 * A verdict belongs where a decision exists. On a bottle you already own the decision was made when
 * you bought it, so a 1-to-5 grade there only ranks your own shelf — the thing this app kept getting
 * wrong. In a shop or at a restaurant there is money and an evening on the line, the label in your
 * hand actually prints its ABV, and "buy / leave it" plus the reasons is the whole answer.
 *
 * So this says exactly two kinds of thing: which of the user's own thresholds the bottle breaks, and
 * which known facts argue for it. A fact the record does not have is never used as evidence in
 * either direction — no invented demerits, no invented enthusiasm.
 */
object BuyAdvice {

    enum class Verdict {
        BUY, LOOK, LEAVE;

        fun label(restaurant: Boolean): String = when (this) {
            BUY -> if (restaurant) "Order it" else "Buy it"
            LOOK -> if (restaurant) "Maybe" else "Worth a look"
            LEAVE -> if (restaurant) "Skip it" else "Leave it"
        }
    }

    /** A shop and a restaurant are different questions: list prices carry a markup, not a deal. */
    enum class Occasion { SHOP, RESTAURANT }

    data class Advice(
        val verdict: Verdict,
        /** Reasons against and for, strongest first. Each one names a fact or a rule. */
        val reasons: List<String>,
        /** What cannot be judged from what the app knows about this bottle. */
        val unknowns: List<String>
    )

    fun forBottle(
        wine: Wine,
        rules: PalateRules,
        profile: PalateProfile,
        cellar: List<Wine>,
        occasion: Occasion
    ): Advice {
        val against = mutableListOf<String>()
        val hard = mutableListOf<String>()
        val forIt = mutableListOf<String>()
        val unknowns = mutableListOf<String>()

        rules.checks(wine).forEach { check ->
            when (check.verdict) {
                // The alcohol ceiling and "no sweet wine" are things the user asked not to be sold;
                // oak is a style they would rather avoid, not a line to walk out over.
                GateVerdict.FAIL -> if (check.gate == PalateRules.GATE_OAK) {
                    against += check.detail
                } else {
                    hard += check.detail
                }
                GateVerdict.PASS -> forIt += check.detail
                GateVerdict.UNKNOWN -> Unit
            }
        }

        // ABV is the reason a shop scan is worth more than a cellar page: the label prints it.
        if (wine.alcohol == null && rules.maxAlcohol != null) {
            unknowns += "no ABV on the label, so the ${fmt(rules.maxAlcohol)}% ceiling cannot be checked"
        }

        when (wine.drinkStatus()) {
            DrinkStatus.PAST -> hard += "past its ${wine.drinkTo} window"
            DrinkStatus.HOLD -> against += "better from ${wine.drinkFrom} — not for tonight"
            DrinkStatus.READY, DrinkStatus.PEAK -> forIt += "drinkable now"
            DrinkStatus.NONE -> Unit
        }

        val owned = cellar.count { it.id != wine.id && sameBottle(it, wine) }
        if (owned > 0) {
            against += "you already have $owned of this at home"
        } else if (cellar.isNotEmpty()) {
            novelty(wine, cellar)?.let { forIt += it }
        }

        if (occasion == Occasion.SHOP) {
            val price = wine.price
            val band = band(profile)
            if (price != null && band != null) {
                val money = formatMoney(price, wine.currency)
                when {
                    band.top != null && price > band.top ->
                        against += "$money — above the ${band.label} you gave me"
                    band.top != null ->
                        forIt += "$money — inside the ${band.label} you gave me"
                    price >= 35.0 -> forIt += "$money — the ${band.label} you gave me"
                    else -> Unit
                }
            }
        }

        val abvUnknown = wine.alcohol == null && rules.maxAlcohol != null
        val verdict = when {
            hard.isNotEmpty() -> Verdict.LEAVE
            // A missing ABV is not a reason to leave it, but it is a reason not to say "buy it":
            // the one limit the user set cannot be checked, so the card asks them to look.
            against.isNotEmpty() || abvUnknown -> Verdict.LOOK
            forIt.isNotEmpty() -> Verdict.BUY
            // Nothing known and nothing broken: say so, rather than dress it up as a match.
            else -> Verdict.LOOK
        }
        // "Leave it" with three reasons to buy underneath would argue with itself.
        val reasons = if (verdict == Verdict.LEAVE) {
            hard.sortedBy { it.length }.distinct().take(3)
        } else {
            (hard.sortedBy { it.length } + against + forIt).distinct().take(3)
        }
        if (reasons.isEmpty()) {
            unknowns += "nothing in the record speaks either way"
        }
        return Advice(verdict, reasons, unknowns.distinct().take(2))
    }

    /** A bottle of the same producer and cuvée is already on the rack. */
    private fun sameBottle(owned: Wine, candidate: Wine): Boolean {
        val a = owned.producer.trim().lowercase() + "|" + owned.name.trim().lowercase()
        val b = candidate.producer.trim().lowercase() + "|" + candidate.name.trim().lowercase()
        if (a != "|" && b != "|" && a == b) return true
        return owned.title.equals(candidate.title, ignoreCase = true)
    }

    /** The one genuinely useful thing a cellar can add to a buying decision: it is a gap. */
    private fun novelty(wine: Wine, cellar: List<Wine>): String? {
        val grape = wine.grapes.firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (grape != null) {
            val has = cellar.any { held ->
                held.quantity > 0 && held.grapes.any { it.lowercase().trim().contains(grape) }
            }
            if (!has) return "no $grape in your cellar"
        }
        val region = wine.region.trim().lowercase().takeIf { it.isNotBlank() }
        if (region != null) {
            val has = cellar.any {
                it.quantity > 0 && it.region.trim().lowercase().let { r -> r.isNotBlank() && r.contains(region) }
            }
            if (!has) return "your first bottle from ${wine.region.trim()}"
        }
        if (wine.type == WineType.ORANGE && cellar.none { it.quantity > 0 && it.type == WineType.ORANGE }) {
            return "no orange wine in your cellar"
        }
        return null
    }

    private data class Band(val label: String, val top: Double?)

    private fun band(profile: PalateProfile): Band? = when (profile.choice(QuickStart.Q_BUDGET)) {
        QuickStart.BUDGET_UNDER_12 -> Band("under \$12", 12.0)
        QuickStart.BUDGET_12_20 -> Band("\$12–20", 20.0)
        QuickStart.BUDGET_20_35 -> Band("\$20–35", 35.0)
        QuickStart.BUDGET_35_UP -> Band("\$35 and up", null)
        else -> null
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}