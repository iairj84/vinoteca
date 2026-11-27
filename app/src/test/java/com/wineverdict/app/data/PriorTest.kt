package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Have I had this?" on a menu. The match is identity — producer, cuvée, vintage, folded for case
 * and accents — and the answer must distinguish a bottle on the shelf from one already drunk.
 */
class PriorTest {

    private fun wine(
        id: String,
        producer: String = "Elowen",
        name: String = "Viognier",
        vintage: Int? = 2025,
        quantity: Int = 1,
        rating: Int? = null
    ) = Wine(
        id = id,
        producer = producer,
        name = name,
        vintage = vintage,
        quantity = quantity,
        rating = rating
    )

    private fun yesterday(): Long =
        LocalDate.now()
            .minusDays(1)
            .atTime(20, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun aWineOnTheShelfSaysSo() {
        val prior = priorFor(wine("scan"), listOf(wine("own", quantity = 2)), emptyList())

        assertEquals(2, prior?.bottles)
        assertEquals("In your cellar · 2 bottles", prior?.summary)
    }

    @Test
    fun aWineDrunkLastNightIsRememberedWithItsWord() {
        val drink = Drink(id = "d", wineId = "own", title = "2025 Viognier · Elowen", rating = Verdict.LOVED.rating, at = yesterday())
        val prior = priorFor(wine("scan"), listOf(wine("own", quantity = 0)), listOf(drink))

        assertEquals("You had this yesterday", prior?.summary)
        assertEquals("Loved", prior?.verdict)
    }

    @Test
    fun aWineOwnedAndDrunkSaysBoth() {
        // Not "gone" and not "never opened": the count says how many are left, the log says when one
        // was opened, and the menu card has room for one line.
        val drink = Drink(id = "d", wineId = "own", at = yesterday())
        val prior = priorFor(wine("scan"), listOf(wine("own", quantity = 1)), listOf(drink))

        assertEquals("In your cellar · you had it yesterday", prior?.summary)
    }

    @Test
    fun aWineNeverSeenStaysSilent() {
        assertNull(priorFor(wine("scan"), listOf(wine("own", name = "Chablis")), emptyList()))
    }

    @Test
    fun identityFoldsCaseAccentsAndTheMenuSCopy() {
        val scanned = wine("menu-id", producer = "VINA OLABARRI", name = "Rioja Reserva", vintage = 2019)
        val own = wine("own", producer = "Viña Olabarri", name = "Rioja Reserva", vintage = 2019, quantity = 3)

        val prior = priorFor(scanned, listOf(own), emptyList())

        assertTrue(prior!!.summary, prior.summary.contains("3 bottles"))
        // A different vintage is a different wine, even from the same producer.
        assertNull(priorFor(scanned.copy(vintage = 2020), listOf(own), emptyList()))
    }
}
