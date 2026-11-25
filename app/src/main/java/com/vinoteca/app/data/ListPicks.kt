package com.vinoteca.app.data

import com.vinoteca.app.model.Wine

/**
 * Ranking one wine list against itself.
 *
 * A per-bottle verdict answers "is anything wrong with this one". On a page that prints no ABV and
 * nothing about oak, that answer comes out the same for every line — nine cards saying the same
 * word, which is no answer at all when the question is which one to order. So a list is ranked.
 *
 * The first attempt ranked on the model's `personal_fit` alone, and it failed the one way that
 * matters: `personal_fit` is a judgment against `mytaste.md`, and with that file empty the model
 * does the honest thing and scores every wine neutral. Nine 3s rank nothing. So the read now also
 * returns `list_rank` — the model's own order down the page, a comparison between the bottles on it
 * rather than a judgment of each in isolation — which stays meaningful with an empty taste file
 * because a Cava and a Trepat rosé can be told apart without knowing who is drinking. This object
 * turns that order into the ranking, with `personal_fit` as the second key and what the app can
 * check locally as the third: a limit of the user's the bottle breaks or clears, its drinking
 * window, whether it is already at home.
 *
 * Nothing is invented to break a tie. When the read comes back level the ranking says so, names the
 * one thing the page does separate — its prices — and crowns nobody. A line that breaks one of the
 * user's limits is out of the running entirely, because second-best of nine is still a
 * recommendation.
 */
/** 4 -> "4th", 11 -> "11th", 22 -> "22nd". */
private fun ordinal(value: Int): String {
    val suffix = when {
        value % 100 in 11..13 -> "th"
        value % 10 == 1 -> "st"
        value % 10 == 2 -> "nd"
        value % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$value$suffix"
}

object ListPicks {

    /** One line of the list, as read. */
    data class Candidate(
        val wine: Wine,
        /** The model's 1-5 fit against `mytaste.md`, when it gave one. */
        val fit: Int? = null,
        /** The model's order down this page: 1 = the bottle it would order first on the list. */
        val order: Int? = null
    )

    /** What the ranking had to go on. */
    enum class Basis {
        /** The read itself put one line above another: an order or a fit that differs. */
        READ,

        /** The read came back level, so the list's own prices are all that separates the lines. */
        PRICE,

        /** Not even the prices differ. */
        NONE
    }

    data class Pick(
        /** 1 for the best on the page, then 2 and 3; null for a line that is not in the running. */
        val rank: Int?,
        /** True when several lines share this rank, so no single bottle is being crowned. */
        val tied: Boolean,
        val score: Int
    ) {
        /**
         * The headline a ranked card carries. Every placed line says where it came, not only the
         * top three: a page of nine with three labels and six identical "Maybe" pills is the same
         * complaint one card further down.
         */
        val label: String
            get() = when (rank) {
                null -> "Maybe"
                1 -> "Best pick"
                2 -> "2nd pick"
                3 -> "3rd pick"
                else -> ordinal(rank)
            }
    }

    data class Ranking(
        /** One pick per candidate, in the order the lines were read. */
        val picks: List<Pick>,
        /** Indexes worth ordering, best first, three at most. */
        val top: List<Int>,
        val basis: Basis,
        /** The cheapest and priciest lines, only when their prices really differ. */
        val cheapest: Int? = null,
        val priciest: Int? = null
    ) {
        /** True when a bottle is actually being put above another. */
        val separated: Boolean
            get() = basis == Basis.READ
    }

    /** The model declining to judge is a neutral bottle, not a bad one. */
    private const val NEUTRAL_FIT = 3

    /** The read's own order is the comparison, so it outweighs every other fact on the page. */
    private const val ORDER_WEIGHT = 100

    /** A fit point is worth more than any single local fact: it was read off this wine. */
    private const val FIT_WEIGHT = 10

    /** Local checks nudge a tie; they must never outvote a full fit point. */
    private const val LOCAL_CAP = 9

    fun rank(
        candidates: List<Candidate>,
        rules: PalateRules,
        profile: PalateProfile,
        cellar: List<Wine>
    ): Ranking {
        if (candidates.isEmpty()) return Ranking(emptyList(), emptyList(), Basis.NONE)

        val advice = candidates.map { candidate ->
            BuyAdvice.forBottle(
                wine = candidate.wine,
                rules = rules,
                profile = profile,
                cellar = cellar,
                occasion = BuyAdvice.Occasion.RESTAURANT
            )
        }
        val out = advice.map { it.verdict == BuyAdvice.Verdict.LEAVE }
        val count = candidates.size

        // What the read said, without the local checks: the order down the page, then the fit.
        // A missing order is not last place — it is a line the read did not place, so it scores as
        // if unranked and the fit decides.
        val readScore = candidates.indices.map { index ->
            val order = candidates[index].order
            val placed = if (order != null) (count - order + 1) * ORDER_WEIGHT else 0
            val fit = candidates[index].fit ?: NEUTRAL_FIT
            placed + fit * FIT_WEIGHT
        }
        val liveRead = candidates.indices.filter { !out[it] }.map { readScore[it] }
        // One read score for the whole page means the model told them apart no better than the user
        // could by reading the list: there is nothing to rank, and no bottle to crown.
        val readSeparated = liveRead.distinct().size > 1

        // The score a card is sorted by: the read, nudged by what the app can check for itself.
        val scores = candidates.indices.map { index ->
            if (out[index]) null else readScore[index] + advice[index].strength.coerceIn(-LOCAL_CAP, LOCAL_CAP)
        }

        val distinct = scores.filterNotNull().distinct().sortedDescending()
        val rankOf = if (readSeparated) {
            distinct.take(3).mapIndexed { index, score -> score to index + 1 }.toMap()
        } else {
            emptyMap()
        }
        val picks = scores.map { score ->
            val rank = if (score == null || !readSeparated) null else rankOf[score]
            Pick(
                rank = rank,
                tied = rank != null && scores.count { it == score } > 1,
                score = score ?: 0
            )
        }
        val top = picks.indices
            .filter { picks[it].rank != null && picks[it].rank!! <= 3 }
            .sortedBy { picks[it].rank }
            .take(3)

        // What is left to tell the lines apart when the read did not: the page's own prices.
        val priced = candidates.indices.filter { !out[it] && candidates[it].wine.price != null }
        val cheap = priced.minByOrNull { candidates[it].wine.price!! }
        val dear = priced.maxByOrNull { candidates[it].wine.price!! }
        val pricedApart = cheap != null && dear != null &&
            candidates[cheap].wine.price!! < candidates[dear].wine.price!!
        val basis = when {
            readSeparated -> Basis.READ
            pricedApart -> Basis.PRICE
            else -> Basis.NONE
        }
        return Ranking(
            picks = picks,
            top = top,
            basis = basis,
            cheapest = if (pricedApart) cheap else null,
            priciest = if (pricedApart) dear else null
        )
    }
}
