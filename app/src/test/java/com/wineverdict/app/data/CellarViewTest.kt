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
        region: String = "",
        addedAt: Long = 0,
        price: Double? = null
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
        region = region,
        addedAt = addedAt,
        price = price
    )

    private fun shown(
        wines: List<Wine>,
        filter: CellarFilter = CellarFilter.ALL,
        query: String = "",
        sort: CellarSort = CellarSort.HOUSE
    ): List<String> = CellarView.visible(wines, CellarData(), query, filter, sort).map { it.id }

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
    fun aSortLabelFromASavedScreenStillFindsItsOrder() {
        assertEquals(CellarSort.PRICE, CellarSort.of("Highest price"))
        assertEquals(CellarSort.HOUSE, CellarSort.of("By colour"))
        assertEquals(CellarSort.HOUSE, CellarSort.of(null))
    }

    @Test
    fun theHouseOrderIsStillWhatTheListOpensOn() {
        val wines = listOf(wine(name = "B", producer = "A"), wine(name = "A", producer = "Z"))
        val house = CellarView.visible(wines, CellarData(), "", CellarFilter.ALL).map { it.id }
        val explicitHouse = CellarView.visible(
            wines,
            CellarData(),
            "",
            CellarFilter.ALL,
            CellarSort.HOUSE
        ).map { it.id }
        assertEquals(explicitHouse, house)
        assertEquals(listOf(wines[0].id, wines[1].id), house)
    }

    @Test
    fun recentlyAddedPutsTheNewestFirst() {
        val older = wine(name = "Older", addedAt = 1_000)
        val newest = wine(name = "Newest", addedAt = 9_000)
        val middle = wine(name = "Middle", addedAt = 5_000)
        assertEquals(
            listOf(newest.id, middle.id, older.id),
            shown(listOf(older, middle, newest), sort = CellarSort.ADDED)
        )
    }

    @Test
    fun producerSortIsAlphabeticalThenNewest() {
        val chianti = wine(name = "Chianti", producer = "Felsina", vintage = 2019)
        val barolo = wine(name = "Barolo", producer = "Burlotto", vintage = 2018)
        val youngerBarolo = wine(name = "Barolo", producer = "Burlotto", vintage = 2016)
        assertEquals(
            listOf(barolo.id, youngerBarolo.id, chianti.id),
            shown(listOf(chianti, youngerBarolo, barolo), sort = CellarSort.PRODUCER)
        )
    }

    @Test
    fun vintageSortPutsTheNewestFirstAndNonVintageLast() {
        val nv = wine(name = "Cava", vintage = null)
        val old = wine(name = "Rioja", vintage = 2012)
        val young = wine(name = "Rioja", vintage = 2021)
        assertEquals(
            listOf(young.id, old.id, nv.id),
            shown(listOf(nv, old, young), sort = CellarSort.VINTAGE)
        )
    }

    @Test
    fun priceSortPutsTheDearestFirstAndAnUnpricedBottleLast() {
        val unpriced = wine(name = "Gift", price = null)
        val cheap = wine(name = "Everyday", price = 12.0)
        val dear = wine(name = "Treasure", price = 180.0)
        assertEquals(
            listOf(dear.id, cheap.id, unpriced.id),
            shown(listOf(unpriced, cheap, dear), sort = CellarSort.PRICE)
        )
    }

    @Test
    fun theSortOnlyReordersWhatTheFilterAndSearchKept() {
        val roseOne = wine(name = "A rosé", type = WineType.ROSE, price = 30.0)
        val roseTwo = wine(name = "Z rosé", type = WineType.ROSE, price = 10.0)
        val red = wine(name = "A red", type = WineType.RED, price = 99.0)
        assertEquals(
            listOf(roseOne.id, roseTwo.id),
            shown(listOf(roseOne, roseTwo, red), CellarFilter.ROSE, sort = CellarSort.PRICE)
        )
    }

    @Test
    fun theHouseOrderFallsBackToTheProducer() {
        val bodegas = wine(name = "Same", producer = "Bodegas")
        val alvarez = wine(name = "Same", producer = "Alvarez")
        assertEquals(listOf(alvarez.id, bodegas.id), shown(listOf(bodegas, alvarez)))
    }
}
