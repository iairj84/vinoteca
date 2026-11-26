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
    fun aFollowUpKeepsTheDishFromTheTurnBefore() {
        // A profile that would supply no dish of its own, so the fallback cannot be what passes.
        val onItsOwn = PalateProfile(
            answers = mapOf(QuickStart.Q_CONTEXT to listOf(QuickStart.CTX_ALONE))
        )
        val tikka = "What should I open with chicken tikka?"
        val followUp = "what about the second bottle"

        assertEquals(Dish("tikka", spicy = true), PairingTable.askDish(tikka, emptyList(), onItsOwn))
        assertEquals(
            "the earlier dish carries into the follow-up, not the food answer",
            Dish("tikka", spicy = true),
            PairingTable.askDish(followUp, listOf(tikka), onItsOwn)
        )

        // And the gate check on that second turn uses it: 14.2% is conditional, not a failure.
        val second = PairingTable.askDish(followUp, listOf(tikka), onItsOwn)
        val digest = rules.digestNote(wine(14.2), second)
        assertTrue(digest, digest.contains("G1 Alcohol conditional"))

        val system = Prompts.chat(
            tasteMd = "taste",
            cellar = CellarData(wines = listOf(wine(14.2))),
            currency = "USD",
            rules = rules,
            dish = second
        )
        assertTrue(system, system.contains("G1 Alcohol conditional"))
    }

    @Test
    fun aNewDishInTheFollowUpWinsAndTheNewestEarlierOneIsUsed() {
        val takeout = PalateProfile(
            answers = mapOf(QuickStart.Q_CONTEXT to listOf(QuickStart.CTX_TAKEOUT))
        )
        val roast = PairingTable.askDish(
            "what about the roast chicken",
            listOf("a red for chicken tikka"),
            takeout
        )
        assertNotNull(roast)
        assertFalse("the question's own dish wins over the earlier one", roast!!.spicy)

        val newest = PairingTable.askDish(
            "what about the second bottle",
            listOf("a red for chicken tikka", "and with the roast chicken?"),
            takeout
        )
        assertNotNull(newest)
        assertFalse("the most recent earlier dish is the one kept", newest!!.spicy)
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

        // A bottle already in the cellar is read as a glass, not as a purchase: over the ceiling
        // with no dish is still "drink it, don't buy another", not "do not open this".
        val noDish = Prompts.chat("taste", cellar, "USD", rules)
        assertTrue(noDish, noDish.contains("G1 Alcohol conditional"))
        assertTrue(noDish, noDish.contains("already own this bottle"))
    }
}
