package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9: what a card says once a model has explained which bottles to buy.
 *
 * The verdict answers "should I spend the money", which is the right answer for one bottle in the
 * hand. Under a shelf that already has advice it only repeats itself under every card, so what is
 * left is the facts the app knows and the advice cannot: a limit broken, a window passed, a bottle
 * already at home. A fact the record does not carry — no printed ABV — is not a warning.
 */
class BuyAdviceChipsTest {

    private fun wine(
        producer: String = "Olabarri",
        name: String = "Reserva",
        alcohol: Double? = null,
        drinkFrom: Int? = null,
        drinkTo: Int? = null
    ) = Wine(
        producer = producer,
        name = name,
        type = WineType.RED,
        alcohol = alcohol,
        drinkFrom = drinkFrom,
        drinkTo = drinkTo,
        currency = "USD"
    )

    @Test
    fun aBrokenCeilingIsAChip() {
        val chips = BuyAdvice.chips(wine(alcohol = 14.8), PalateRules(maxAlcohol = 13.5), emptyList())
        assertEquals(1, chips.size)
        assertTrue(chips[0].warning)
        assertTrue(chips[0].text.contains("14.8"))
    }

    @Test
    fun aCeilingTheBottleClearsIsNeverAChip() {
        assertTrue(
            BuyAdvice.chips(wine(alcohol = 12.5), PalateRules(maxAlcohol = 13.5), emptyList()).isEmpty()
        )
    }

    @Test
    fun anUnknownAbvIsNeverAChip() {
        // The same rule as the verdict: a fact the record cannot check is not a demerit.
        assertTrue(
            BuyAdvice.chips(wine(alcohol = null), PalateRules(maxAlcohol = 13.5), emptyList()).isEmpty()
        )
    }

    @Test
    fun aBottleAlreadyAtHomeIsAChip() {
        val chips = BuyAdvice.chips(wine(), PalateRules(), listOf(wine()))
        assertEquals(1, chips.size)
        assertFalse(chips[0].warning)
        assertTrue(chips[0].text.contains("already"))
    }

    @Test
    fun theSameRecordCountsAsOwnedNotAsOneself() {
        // A scan hands its own wine object to the cellar, so the stored record carries the same id
        // as the card's. Excluding that id -- which [BuyAdvice.forBottle] does, for the bottle's own
        // page -- would hide the one fact the chip exists for.
        val bottle = wine()
        val chips = BuyAdvice.chips(bottle, PalateRules(), listOf(bottle))
        assertEquals(1, chips.size)
        assertTrue(chips[0].text.contains("already"))
    }

    @Test
    fun aPastWindowIsAChip() {
        val chips = BuyAdvice.chips(wine(drinkFrom = 2005, drinkTo = 2014), PalateRules(), emptyList())
        assertEquals(1, chips.size)
        assertTrue(chips[0].warning)
        assertTrue(chips[0].text.contains("2014"))
    }

    @Test
    fun noRulesNoCellarAndNoWindowMeansNoChip() {
        assertTrue(BuyAdvice.chips(wine(), PalateRules(), emptyList()).isEmpty())
    }
}
