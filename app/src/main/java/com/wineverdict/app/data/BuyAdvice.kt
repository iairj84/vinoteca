package com.wineverdict.app.data

import com.wineverdict.app.model.DrinkStatus
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.drinkStatus
import com.wineverdict.app.model.formatMoney

/**
 * What to do with a bottle that is not in the cellar yet.
 *
 * A verdict belongs where a decision exists. On a bottle you already own the decision was made when
 * you bought it, so a 1-to-5 grade there only ranks your own shelf — the thing this app kept getting
 * wrong. In a shop or at a restaurant there is money and an evening on the line, the label in your
 * hand actually prints its ABV, and "buy / leave it" plus the reasons is the whole answer.
 *
 * The rule that keeps the verdict worth reading: **a limit the bottle clears is not a reason to buy
 * it, and neither is a gap it would fill.** Being under an alcohol ceiling is a filter passed, and
 * "your first bottle from Catalonia" is a reason to be curious; treating either as an argument for
 * the money is what made a nine-bottle wine list come back as nine bottle pills. Only a fact
 * that argues for spending the money — in practice a price inside the band the user gave — can
 * produce a Buy it. A passed ceiling is not one either: if compliance were an argument, every wine
 * under the ceiling would say buy, which is the same complaint one layer down.
 *
 * So this says three kinds of thing: which of the user's own thresholds the bottle breaks, which
 * facts argue for it, and what it would add to the cellar. A fact the record does not have is never
 * used as evidence in either direction — no invented demerits, no invented enthusiasm.
 */
object BuyAdvice {

    enum class Verdict {
        BUY, LOOK, LEAVE;

        fun label(restaurant: Boolean): String = when (this) {
            BUY -> if (restaurant) "Order the bottle" else "Buy it"
            LOOK -> if (restaurant) "Maybe" else "Worth a look"
            LEAVE -> if (restaurant) "Skip it" else "Leave it"
        }
    }

    /** A shop and a restaurant are different questions: list prices carry a markup, not a deal. */
    enum class Occasion { SHOP, RESTAURANT }

    data class Advice(
        val verdict: Verdict,
        /** The lines that explain the verdict, strongest first. Each one names a fact or a rule. */
        val reasons: List<String>,
        /** What the bottle would add to the cellar. Never, on its own, a reason to buy it. */
        val gaps: List<String>,
        /** What cannot be judged from what the app knows about this bottle. */
        val unknowns: List<String>,
        /**
         * How strongly the record argues for this bottle, for ranking one list against itself.
         * Never shown as a score — [ListPicks] sorts cards with it and nothing else reads it.
         */
        val strength: Int = 0
    )

    /**
     * A fact worth flagging on a card that already carries advice.
     *
     * [Advice] answers "should I spend the money", which is the right answer to a photo of one
     * bottle in the hand. Once a model has explained which bottles on a shelf to buy, a second
     * headline under every card is exactly the noise the advice replaced — so all that is left is
     * what the app knows for certain and the advice does not: a limit the bottle breaks, a window
     * it has passed, a bottle already at home. A gate the record cannot check (no ABV printed) is
     * never a chip: that is a fact about the label, not a warning about the wine.
     */
    data class Chip(val text: String, val warning: Boolean)

    fun chips(wine: Wine, rules: PalateRules, cellar: List<Wine>): List<Chip> {
        val out = mutableListOf<Chip>()
        rules.checks(wine)
            .filter { it.verdict == GateVerdict.FAIL }
            .forEach { out += Chip(it.detail, true) }
        if (wine.drinkStatus() == DrinkStatus.PAST) {
            out += Chip("past its ${wine.drinkTo} window", true)
        }
        // Unlike [forBottle], the id is not excluded: a scan card's wine often *is* the cellar
        // record (the scan hands its own object to the cellar, id and all), and "this bottle is
        // already at home" is exactly what the card should say about it.
        val owned = cellar.count { sameBottle(it, wine) }
        if (owned > 0) {
            out += Chip(if (owned == 1) "already in your cellar" else "already have $owned", false)
        }
        return out.distinctBy { it.text }.take(3)
    }

    fun forBottle(
        wine: Wine,
        rules: PalateRules,
        profile: PalateProfile,
        cellar: List<Wine>,
        occasion: Occasion,
        /** The dish this bottle is for, when one is known: it is what makes a 14.2% bottle a pass. */
        dish: Dish? = null
    ): Advice {
        // Four buckets, because they are four different claims and only one of them can turn into
        // Buy it. `hard` walks away from the bottle, `against` advises against this evening,
        // `argues` is a fact that argues for the money, and `clears` is every limit the bottle
        // passes — compliance, which is shown but never counted as enthusiasm.
        val hard = mutableListOf<String>()
        val against = mutableListOf<String>()
        val clears = mutableListOf<String>()
        val argues = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        val unknowns = mutableListOf<String>()

        // Every gate that can fail is one the user set by name, so breaking it is a reason to walk
        // away rather than a style to weigh up. Oak used to be a soft "against"; it is a preference
        // in the brief now and no longer a test the app runs.
        // All four authored gates, in order, before anything is scored. A conditional is a pass
        // whose basis is printed, so it belongs with what the bottle clears.
        val gates = rules.verdicts(wine, dish)
        gates.forEach { check ->
            when (check.verdict) {
                GateVerdict.FAIL -> hard += check.detail
                GateVerdict.PASS, GateVerdict.CONDITIONAL -> clears += check.detail
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
            DrinkStatus.READY, DrinkStatus.PEAK -> clears += "drinkable now"
            DrinkStatus.NONE -> Unit
        }

        val owned = cellar.count { it.id != wine.id && sameBottle(it, wine) }
        if (owned > 0) {
            against += "you already have $owned of this at home"
        } else if (cellar.isNotEmpty()) {
            novelty(wine, cellar)?.let { gaps += it }
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
                        argues += "$money — inside the ${band.label} you gave me"
                    price >= 35.0 -> argues += "$money — the ${band.label} you gave me"
                    else -> Unit
                }
            }
        }

        // A price inside the user's own band is the one fact here that argues for the money. A
        // cleared limit is compliance, so it earns a line under the verdict and not the verdict.
        val arguesForIt = argues.isNotEmpty()
        // A gate that could not be checked is not a demerit. It used to hedge a bottle with no
        // printed ABV down to "Maybe", which punished the record for a fact the list never prints.
        val verdict = when {
            hard.isNotEmpty() -> Verdict.LEAVE
            against.isNotEmpty() -> Verdict.LOOK
            arguesForIt -> Verdict.BUY
            // Nothing known and nothing broken: say so, rather than dress it up as a match.
            else -> Verdict.LOOK
        }
        // "Leave it" with three reasons to buy underneath would argue with itself.
        val ordered = when (verdict) {
            Verdict.LEAVE -> hard.sortedBy { it.length }
            // What argues for the bottle first, then what argues against this evening, then the
            // limits it passes.
            Verdict.BUY -> argues + against + clears
            Verdict.LOOK -> against + clears
        }
        val reasons = ordered.distinct().take(3)
        if (reasons.isEmpty() && gaps.isEmpty()) {
            unknowns += "nothing in the record speaks either way"
        }
        // A fact that argues for the money counts most, a limit passed counts a little, and a bottle
        // already at home or due a few years' sleep counts against it. A line that breaks a limit is
        // out of the ranking altogether, which ListPicks handles from the verdict.
        // An unknown gate is not a pass. It is not a demerit on the verdict either, but it does
        // rank below a bottle whose number is on record.
        val unproven = gates.count { it.verdict == GateVerdict.UNKNOWN && it.configured }
        val strength = if (hard.isNotEmpty()) {
            0
        } else {
            argues.size * 4 + clears.size * 2 - against.size * 3 - unproven
        }
        return Advice(
            verdict = verdict,
            reasons = reasons,
            gaps = gaps.distinct().take(2),
            unknowns = unknowns.distinct().take(2),
            strength = strength
        )
    }

    /**
     * A bottle of the same producer and cuvée is already on the rack.
     */
    private fun sameBottle(owned: Wine, candidate: Wine): Boolean {
        val a = owned.producer.trim().lowercase() + "|" + owned.name.trim().lowercase()
        val b = candidate.producer.trim().lowercase() + "|" + candidate.name.trim().lowercase()
        if (a != "|" && b != "|" && a == b) return true
        return owned.title.equals(candidate.title, ignoreCase = true)
    }

    /**
     * The one genuinely useful thing a cellar can add to a buying decision, minus the claim it is
     * worth the money: a gap. It is read out here and shown quietly; the verdict does not move.
     */
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
