package com.vinoteca.app.data

import com.vinoteca.app.model.Wine

/**
 * Ranking one wine list against itself.
 *
 * A per-bottle verdict answers "is anything wrong with this one". On a page that prints no ABV and
 * nothing about oak, that answer comes out the same for every line — nine cards saying the same
 * word, which is no answer at all when the question is which one to order. So a list is ranked
 * instead. Every line is scored from the two things there are: the fit the model read off that wine
 * against `mytaste.md` (1-5, per bottle), and what the app can check for itself — a limit of the
 * user's the bottle breaks or clears, its drinking window, whether it is already at home.
 *
 * This is the ranking the cellar page is deliberately without. There the question was settled at the
 * till and a score only measured the record; here an evening and a bill are on the line, and the
 * comparison is between the wines on the page rather than between a wine and a number.
 *
 * Nothing is invented to break a tie. When every line scores the same the ranking says so instead of
 * crowning an arbitrary bottle, and a line that breaks one of the user's limits is out of the running
 * entirely — second-best of nine is still a recommendation.
 */
object ListPicks {

    /** One line of the list, as read. */
    data class Candidate(
        val wine: Wine,
        /** The model's 1-5 fit against `mytaste.md`, when it gave one. */
        val fit: Int? = null
    )

    data class Pick(
        /** 1 for the best on the page, then 2 and 3; null for a line that is not in the running. */
        val rank: Int?,
        /** True when several lines share this rank, so no single bottle is being crowned. */
        val tied: Boolean,
        val score: Int
    ) {
        /** The headline a ranked card carries. */
        val label: String
            get() = when (rank) {
                1 -> "Best pick"
                2 -> "2nd pick"
                3 -> "3rd pick"
                else -> "Maybe"
            }
    }

    data class Ranking(
        /** One pick per candidate, in the order the lines were read. */
        val picks: List<Pick>,
        /** Indexes worth ordering, best first, three at most. */
        val top: List<Int>,
        /** False when nothing on the page could be told apart. */
        val separated: Boolean
    )

    /** The model declining to judge is a neutral bottle, not a bad one. */
    private const val NEUTRAL_FIT = 3

    /** A fit point is worth more than any single local fact: it was read off this wine. */
    private const val FIT_WEIGHT = 4

    fun rank(
        candidates: List<Candidate>,
        rules: PalateRules,
        profile: PalateProfile,
        cellar: List<Wine>
    ): Ranking {
        val scores = candidates.map { candidate ->
            val advice = BuyAdvice.forBottle(
                wine = candidate.wine,
                rules = rules,
                profile = profile,
                cellar = cellar,
                occasion = BuyAdvice.Occasion.RESTAURANT
            )
            if (advice.verdict == BuyAdvice.Verdict.LEAVE) {
                null
            } else {
                (candidate.fit ?: NEUTRAL_FIT) * FIT_WEIGHT + advice.strength
            }
        }

        val live = scores.filterNotNull()
        val distinct = live.distinct().sortedDescending()
        // One score for the whole page means the read told them apart no better than the user could
        // by reading the list, so there is nothing to rank and no bottle to crown.
        val separated = distinct.size > 1
        val rankOf = if (separated) {
            distinct.take(3).mapIndexed { index, score -> score to index + 1 }.toMap()
        } else {
            emptyMap()
        }

        val picks = scores.map { score ->
            if (score == null || !separated) {
                Pick(rank = null, tied = false, score = score ?: 0)
            } else {
                val rank = rankOf[score]
                Pick(rank = rank, tied = rank != null && live.count { it == score } > 1, score = score)
            }
        }

        val top = picks.indices
            .filter { picks[it].rank != null }
            .sortedBy { picks[it].rank ?: Int.MAX_VALUE }
            .take(3)

        return Ranking(picks = picks, top = top, separated = separated)
    }
}
