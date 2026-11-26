package com.wineverdict.app.data

import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowances have to exist outside the unit tests that assert them.
 *
 * Both the question the user just asked and the standing food-context answer name a dish, and that
 * dish is what turns "14.2% fails your 14.0 ceiling" into a pass that prints its number — in the
 * digest a model reads and in the app's own verdict on a bottle.
 */
class DishGateTest {

    private val rules = PalateRules(maxAlcohol = 14.0)

    private fun wine(abv: Double?) = Wine(
        producer = "Test",
        name = "Syrah",
        vintage = 2021,
        region = "Rhône",
        country = "France",
        alcohol = abv,
        quantity = 1,
        currency = "USD"
    )

    private fun profile() = PalateProfile(
        answers = mapOf(QuickStart.Q_BUDGET to listOf(QuickStart.BUDGET_20_35))
    )

    @Test
    fun theQuestionNamesTheDish() {
        assertEquals(Dish("tikka", spicy = true), Dish.fromQuestion("a red for chicken tikka"))

        val roast = Dish.fromQuestion("what goes with roast chicken?")
        assertNotNull(roast)
        assertFalse(roast!!.spicy)

        assertNull("a bottle on its own has no dish", Dish.fromQuestion("what should I open tonight?"))
        assertNull("price is not rice", Dish.fromQuestion("what is a fair price?"))
    }

    @Test
    fun theFoodAnswerSuppliesAStandingDish() {
        assertTrue(PairingTable.dishFor(QuickStart.CTX_TAKEOUT)!!.spicy)
        assertFalse(PairingTable.dishFor(QuickStart.CTX_WEEKEND)!!.spicy)
        assertNull("wine on its own names no dish", PairingTable.dishFor(QuickStart.CTX_ALONE))
        assertNull(PairingTable.dishFor(null))
    }

    @Test
    fun aDishTurnsTheCeilingIntoAConditionalInTheDigest() {
        val withFood = rules.digestNote(wine(14.2), Dish("tikka", spicy = true))
        assertTrue(withFood, withFood.contains("G1 Alcohol conditional"))
        assertTrue(withFood, withFood.contains("14.2"))

        val alone = rules.digestNote(wine(14.2))
        assertTrue(alone, alone.contains("G1 Alcohol fails"))
    }

    @Test
    fun buyAdviceKeepsTheBottleWhenThereIsFood() {
        val withFood = BuyAdvice.forBottle(
            wine = wine(14.2),
            rules = rules,
            profile = profile(),
            cellar = emptyList(),
            occasion = BuyAdvice.Occasion.RESTAURANT,
            dish = Dish("roast chicken")
        )
        assertNotEquals(BuyAdvice.Verdict.LEAVE, withFood.verdict)

        val alone = BuyAdvice.forBottle(
            wine = wine(14.2),
            rules = rules,
            profile = profile(),
            cellar = emptyList(),
            occasion = BuyAdvice.Occasion.RESTAURANT
        )
        assertEquals("no dish, no allowance", BuyAdvice.Verdict.LEAVE, alone.verdict)
    }

    @Test
    fun theChatPromptCarriesTheDishAndTheConditionalLine() {
        val cellar = CellarData(wines = listOf(wine(14.2)))
        val system = Prompts.chat(
            tasteMd = "taste",
            cellar = cellar,
            currency = "USD",
            rules = rules,
            dish = Dish("chicken tikka", spicy = true)
        )
        assertTrue(system, system.contains("G1 Alcohol conditional"))
        assertTrue(system, system.contains("The dish in this question: chicken tikka, spicy"))

        val noDish = Prompts.chat("taste", cellar, "USD", rules)
        assertTrue(noDish, noDish.contains("G1 Alcohol fails"))
    }
}
