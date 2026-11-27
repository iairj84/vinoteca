package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.MoveRequest
import com.wineverdict.app.model.StorageLocation
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A move query resolved against the cellar.
 *
 * The reason this is a query and not a list of names: the chat only ever sees a digest of the shelf,
 * so a list it wrote would silently be short. These pin the rest of the hard rules — a request that
 * names nothing resolves to nothing, a bottle already there is not moved again, and a bottle with no
 * stock is not on any shelf to move.
 */
class MoveMatchTest {

    private val bar = StorageLocation(id = "bar", name = "Bar Fridge")
    private val rack = StorageLocation(id = "rack", name = "Main rack")

    private fun bottle(
        producer: String = "Prospice",
        name: String = "Cabernet Sauvignon",
        grape: String = "Cabernet Sauvignon",
        vintage: Int? = 2019,
        type: WineType = WineType.RED,
        quantity: Int = 2,
        locationId: String? = null,
        slot: String = ""
    ) = Wine(
        producer = producer,
        name = name,
        grapes = if (grape.isBlank()) emptyList() else listOf(grape),
        vintage = vintage,
        type = type,
        quantity = quantity,
        locationId = locationId,
        slot = slot
    )

    private fun cellar(wines: List<Wine>) = CellarData(wines = wines, locations = listOf(bar, rack))

    @Test
    fun aGrapeQueryFindsEveryBottleNotJustTheOnesInTheDigest() {
        val wines = List(60) { bottle() } + bottle(name = "Cabernet Franc", grape = "Cabernet Franc")
        val plan = MoveRequest(grape = "Cabernet Sauvignon", location = "Bar Fridge").plan(cellar(wines))
        assertEquals(60, plan?.wines?.size)
        assertEquals("bar", plan?.location?.id)
    }

    @Test
    fun aRequestWithNothingToMatchOnIsRefused() {
        assertNull(MoveRequest(location = "Bar Fridge").plan(cellar(listOf(bottle()))))
    }

    @Test
    fun aRequestWithNoPlaceIsRefused() {
        assertNull(MoveRequest(grape = "Cabernet Sauvignon").plan(cellar(listOf(bottle()))))
    }

    @Test
    fun bottlesAlreadyThereAreCountedRatherThanMovedAgain() {
        val wines = listOf(bottle(locationId = "bar"), bottle())
        val plan = MoveRequest(grape = "Cabernet Sauvignon", location = "Bar Fridge").plan(cellar(wines))
        assertEquals(1, plan?.wines?.size)
        assertEquals(1, plan?.alreadyThere?.size)
        assertEquals(false, plan?.createsLocation)
    }

    @Test
    fun aPlaceTheCellarDoesNotHaveIsProposedForCreation() {
        val plan = MoveRequest(grape = "Cabernet Sauvignon", location = "Cellar door")
            .plan(cellar(listOf(bottle())))
        assertNull(plan?.location)
        assertEquals(true, plan?.createsLocation)
        assertEquals("Cellar door", plan?.locationName)
        assertEquals(1, plan?.wines?.size)
    }

    @Test
    fun thePlaceIsReadWithoutCaseOrAccents() {
        val plan = MoveRequest(grape = "cabernet sauvignon", location = "bar fridge")
            .plan(cellar(listOf(bottle())))
        assertEquals("bar", plan?.location?.id)
        assertEquals(1, plan?.wines?.size)
    }

    @Test
    fun aBottleMarkedGoneIsNotMoved() {
        val plan = MoveRequest(grape = "Cabernet Sauvignon", location = "Bar Fridge")
            .plan(cellar(listOf(bottle(quantity = 0))))
        assertTrue(plan?.wines?.isEmpty() == true)
        assertTrue(plan?.alreadyThere?.isEmpty() == true)
    }

    @Test
    fun aTypeNarrowsTheMatchAndAWordThatIsNotATypeDoesNot() {
        val mixed = cellar(
            listOf(
                bottle(),
                bottle(name = "Chardonnay", grape = "Chardonnay", type = WineType.WHITE)
            )
        )
        val white = MoveRequest(grape = "Chardonnay", type = "White", location = "Bar Fridge").plan(mixed)
        assertEquals(1, white?.wines?.size)
        // "Cabernet" is not one of the seven types, so it must narrow nothing rather than read as Red.
        val notAType = MoveRequest(type = "Cabernet", location = "Bar Fridge").plan(mixed)
        assertEquals(2, notAType?.wines?.size)
    }

    @Test
    fun aVintageRangeLeavesANonVintageBottleOut() {
        val mixed = cellar(
            listOf(
                bottle(vintage = 2019),
                bottle(name = "Blanc", grape = "Blanc", vintage = null)
            )
        )
        val plan = MoveRequest(producer = "Prospice", vintageFrom = 2019, location = "Bar Fridge")
            .plan(mixed)
        assertEquals(1, plan?.wines?.size)
    }

    @Test
    fun theProducerAndTheCuveeBothSearchTheTwoNameHalves() {
        val wines = cellar(listOf(bottle(producer = "Prospice", name = "Phinny Hill Vineyard")))
        assertEquals(1, MoveRequest(name = "Prospice", location = "Bar Fridge").plan(wines)?.wines?.size)
        assertEquals(1, MoveRequest(producer = "Phinny Hill", location = "Bar Fridge").plan(wines)?.wines?.size)
    }

    @Test
    fun aRegionAlsoReadsTheAppellation() {
        val wine = bottle().copy(region = "", appellation = "Walla Walla Valley")
        val plan = MoveRequest(region = "Walla Walla", location = "Bar Fridge").plan(cellar(listOf(wine)))
        assertEquals(1, plan?.wines?.size)
    }

    @Test
    fun aBottleAlreadyInTheSlotCountsAsThere() {
        val wines = listOf(bottle(locationId = "bar", slot = "Top shelf"))
        val same = MoveRequest(grape = "Cabernet Sauvignon", location = "Bar Fridge", slot = "Top shelf")
        assertEquals(1, same.plan(cellar(wines))?.alreadyThere?.size)
        val elsewhere = MoveRequest(grape = "Cabernet Sauvignon", location = "Bar Fridge", slot = "Bottom")
        assertEquals(1, elsewhere.plan(cellar(wines))?.wines?.size)
    }
}
