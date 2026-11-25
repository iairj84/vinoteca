package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 3: a tap is not a measurement.
 *
 * The quick start used to turn "avoid strong" into a 13.5% ceiling, which then failed the 5★
 * Zinfandel sitting in the same cellar. These tests hold the three promises that replaced it: a chip
 * never invents a number, an unknown answer is never a failure, and a loved bottle that breaks a
 * ceiling the user really set gets said out loud.
 */
class InferredRulesTest {

    private fun profile(
        avoid: List<String> = listOf(QuickStart.AVOID_STRONG),
        budget: String = QuickStart.BUDGET_20_35
    ) = PalateProfile(
        answers = mapOf(
            QuickStart.Q_AVOID to avoid,
            QuickStart.Q_BUDGET to listOf(budget)
        )
    )

    private fun zin(abv: Double?, rating: Int? = 5, favorite: Boolean = false) = Wine(
        producer = "Ravenswood",
        name = "Old Vine Zinfandel",
        vintage = 2021,
        type = WineType.RED,
        region = "California",
        alcohol = abv,
        price = 24.0,
        rating = rating,
        favorite = favorite
    )

    private fun advice(wine: Wine, rules: PalateRules) = BuyAdvice.forBottle(
        wine = wine,
        rules = rules,
        profile = profile(),
        cellar = emptyList(),
        occasion = BuyAdvice.Occasion.SHOP
    )

    @Test
    fun avoidStrongDoesNotBecomeANumber() {
        val rules = PalateRules.from(profile())
        assertNull("a chip must not invent a ceiling", rules.maxAlcohol)
        assertTrue(rules.checks(zin(15.5)).none { it.gate == PalateRules.GATE_ALCOHOL })
    }

    @Test
    fun theFifteenPercentZinfandelIsNotPunished() {
        val rules = PalateRules.from(profile())
        assertFalse(rules.fails(zin(15.5)))
        assertEquals(BuyAdvice.Verdict.BUY, advice(zin(15.5), rules).verdict)
    }

    @Test
    fun unknownAlcoholIsNotAFailure() {
        val rules = PalateRules(maxAlcohol = 13.5)
        assertEquals(
            GateVerdict.UNKNOWN,
            rules.checks(zin(null)).first { it.gate == PalateRules.GATE_ALCOHOL }.verdict
        )
        assertFalse(rules.fails(zin(null)))
    }

    @Test
    fun unknownAlcoholDoesNotDemoteABuy() {
        val rules = PalateRules(maxAlcohol = 13.5)
        val result = advice(zin(null), rules)
        assertEquals(BuyAdvice.Verdict.BUY, result.verdict)
        // Said out loud, but as a gap: it is not allowed to move the verdict.
        assertTrue(result.unknowns.any { it.contains("ABV") })
    }

    @Test
    fun aCeilingTheBottleBreaksStillWalksAway() {
        val rules = PalateRules(maxAlcohol = 13.5)
        assertEquals(BuyAdvice.Verdict.LEAVE, advice(zin(14.5), rules).verdict)
    }

    @Test
    fun aLovedBottleBreakingACeilingIsNamed() {
        val notice = PalateRules(maxAlcohol = 13.5).lovedConflict(listOf(zin(15.0)))
        assertNotNull(notice)
        assertTrue(notice!!.contains("Zinfandel"))
        assertTrue(notice.contains("13.5"))
    }

    @Test
    fun nothingIsSaidWhenTheBottleFitsOrCannotBeChecked() {
        val rules = PalateRules(maxAlcohol = 13.5)
        assertNull(rules.lovedConflict(listOf(zin(12.0))))
        assertNull(rules.lovedConflict(listOf(zin(null))))
    }

    @Test
    fun anUnlovedBottleIsNotANotice() {
        assertNull(PalateRules(maxAlcohol = 13.5).lovedConflict(listOf(zin(15.0, rating = 3))))
    }

    @Test
    fun aFavouriteCountsAsLoved() {
        val notice = PalateRules(maxAlcohol = 13.5)
            .lovedConflict(listOf(zin(15.0, rating = null, favorite = true)))
        assertNotNull(notice)
    }

    @Test
    fun avoidOakDoesNotBecomeAGate() {
        val rules = PalateRules.from(profile(avoid = listOf(QuickStart.AVOID_OAK)))
        assertTrue("a chip must not run a test either", rules.isEmpty)
        assertTrue(rules.checks(zin(13.0)).isEmpty())
    }

    @Test
    fun aTypedCeilingIsTheOneRule() {
        val rules = PalateRules.from(profile().copy(alcoholCeiling = 13.0))
        assertEquals(13.0, rules.maxAlcohol!!, 0.001)
        assertTrue(rules.fails(zin(14.0)))
        assertFalse(rules.fails(zin(12.5)))
    }

    @Test
    fun aTypedCeilingThatFailsALovedBottleIsNoticed() {
        val rules = PalateRules.from(profile().copy(alcoholCeiling = 13.0))
        assertNotNull(rules.lovedConflict(listOf(zin(14.5))))
        assertNull(rules.lovedConflict(listOf(zin(12.0))))
    }

    @Test
    fun aNumberNoWineHasIsIgnored() {
        assertNull(PalateRules.from(profile().copy(alcoholCeiling = 40.0)).maxAlcohol)
        assertNull(PalateRules.from(profile().copy(alcoholCeiling = 0.0)).maxAlcohol)
    }
}
