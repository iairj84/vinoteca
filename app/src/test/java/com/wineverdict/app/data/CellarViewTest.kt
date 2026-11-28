package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Year

/**
 * The cellar's filter row and the order under it, measured without a phone.
 *
 * These are the rules the screen only draws: which chip keeps a bottle, and which bottle comes
 * first. The style chips are checked against [WineType.entries], so a style the app can store but
 * the row cannot reach is a failing test rather than a chip somebody notices is missing in a shop.
 */
class CellarViewTest {

    private fun wine(
        name: String = "A wine",
        producer: String = "A producer",
        type: WineType = WineType.RED,
        vintage: Int? = 2020,
        quantity: Int = 1,
        drinkFrom: Int? = null,
        drinkTo: Int? = null,
        favorite: Boolean = false,
        toBuy: Boolean = false,
        region: String = ""
    ) = Wine(
        producer = producer,
        name = name,
        type = type,
        vintage = vintage,
        quantity = quantity,
        drinkFrom = drinkFrom,
        drinkTo = drinkTo,
        favorite = favorite,
        toBuy = toBuy,
        region = region
    )

    private fun shown(
        wines: List<Wine>,
        filter: CellarFilter = CellarFilter.ALL,
        query: String = ""
    ): List<String> = CellarView.visible(wines, CellarData(), query, filter).map { it.id }

    @Test
    fun everyStyleTheModelCanWriteHasAChipThatFindsIt() {
        val wines = WineType.entries.map { type -> wine(name = type.label, type = type) }
        WineType.entries.forEach { type ->
            val chip = CellarFilter.entries.firstOrNull { it.type == type }
            assertNotNull("no chip for $type", chip)
            assertEquals(
                "$type is not kept by its own chip",
                listOf(type),
                CellarView.visible(wines, CellarData(), "", chip!!).map { it.type }
            )
        }
    }

    @Test
    fun theQuestionsKeepTheFrontOfTheRow() {
        // The strip scrolls, so No bottles must not be pushed off the end by the seven styles: it is
        // the only way to reach a saved wine or a bottle that is all drunk.
        assertTrue(
            CellarFilter.entries.indexOf(CellarFilter.EMPTY) <
                CellarFilter.entries.indexOf(CellarFilter.RED)
        )
        assertEquals(
            listOf("All", "Drink up", "To buy"),
            CellarFilter.entries.take(3).map { it.label }
        )
    }

    @Test
    fun aChipLabelFromASavedScreenStillFindsItsChip() {
        assertEquals(CellarFilter.ROSE, CellarFilter.of("Rosé"))
        assertEquals(CellarFilter.EMPTY, CellarFilter.of("No bottles"))
        assertEquals(CellarFilter.ALL, CellarFilter.of("Nebbiolo"))
        assertEquals(CellarFilter.ALL, CellarFilter.of(null))
    }

    @Test
    fun aStyleChipShowsBottlesWithNoneLeft() {
        // A rosé you finished is still a rosé you want to remember; the style chips are records,
        // not decisions, which is the difference between them and Drink up.
        val rose = wine(name = "Whispering Angel", type = WineType.ROSE, quantity = 0)
        assertEquals(listOf(rose.id), shown(listOf(rose), CellarFilter.ROSE))
    }

    @Test
    fun drinkUpIsOnlyBottlesStillOnTheShelf() {
        val year = Year.now().value
        val finished = wine(name = "Finished", quantity = 0, drinkFrom = year - 10, drinkTo = year - 1)
        val open = wine(name = "Open", quantity = 1, drinkFrom = year - 10, drinkTo = year - 1)
        assertEquals(listOf(open.id), shown(listOf(finished, open), CellarFilter.DRINK_UP))
    }

    @Test
    fun theToBuyChipIsTheFlagAndNothingElse() {
        val flagged = wine(name = "Wanted", toBuy = true)
        val other = wine(name = "Not wanted")
        assertEquals(listOf(flagged.id), shown(listOf(flagged, other), CellarFilter.TO_BUY))
    }

    @Test
    fun theSearchBoxNarrowsWhatTheChipKept() {
        val provence = wine(name = "Whispering Angel", type = WineType.ROSE, region = "Provence")
        val penedes = wine(name = "Llopart", type = WineType.ROSE, region = "Penedès")
        assertEquals(
            listOf(provence.id),
            shown(listOf(provence, penedes), CellarFilter.ROSE, query = "provence")
        )
    }

    @Test
    fun theHouseOrderPutsAHeartFirst() {
        val plain = wine(name = "A", producer = "A")
        val loved = wine(name = "Z", producer = "Z", favorite = true)
        assertEquals(listOf(loved.id, plain.id), shown(listOf(plain, loved)))
    }

    @Test
    fun theHouseOrderPutsStockBeforeAWineWithNoneLeft() {
        val empty = wine(name = "A", producer = "A", quantity = 0)
        val stocked = wine(name = "Z", producer = "Z", quantity = 1)
        assertEquals(listOf(stocked.id, empty.id), shown(listOf(empty, stocked)))
    }

    @Test
    fun theHouseOrderPutsTheEndOfTheWindowFirst() {
        val year = Year.now().value
        val holding = wine(name = "A", producer = "A", drinkFrom = year + 2, drinkTo = year + 8)
        val past = wine(name = "B", producer = "B", drinkFrom = year - 10, drinkTo = year - 1)
        val peak = wine(name = "C", producer = "C", drinkFrom = year - 10, drinkTo = year + 2)
        assertEquals(
            listOf(past.id, peak.id, holding.id),
            shown(listOf(holding, peak, past))
        )
    }

    @Test
    fun theHouseOrderFallsBackToTheProducer() {
        val bodegas = wine(name = "Same", producer = "Bodegas")
        val alvarez = wine(name = "Same", producer = "Alvarez")
        assertEquals(listOf(alvarez.id, bodegas.id), shown(listOf(bodegas, alvarez)))
    }
}
