package com.wineverdict.app.data

import com.wineverdict.app.ai.ProposalParsing
import com.wineverdict.app.model.BottleUpdate
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.StorageLocation
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A field change resolved against the cellar.
 *
 * A change is a move's twin: the same query picks the bottles, and the card then says which fields
 * will be written and what they were. These pin the rules that keep it honest — nothing to change is
 * no proposal, a value that cannot be a record field is refused whole rather than dropped, the old
 * value is only stated when every bottle agrees on it, and a note is added to rather than written
 * over.
 */
class BottleUpdateTest {

    private val bar = StorageLocation(id = "bar", name = "Bar Fridge")
    private val rack = StorageLocation(id = "rack", name = "Main rack")

    private fun bottle(
        producer: String = "Prospice",
        name: String = "Cabernet Sauvignon",
        grape: String = "Cabernet Sauvignon",
        vintage: Int? = 2019,
        type: WineType = WineType.RED,
        quantity: Int = 2,
        price: Double? = 55.0,
        currency: String = "USD",
        locationId: String? = null,
        slot: String = ""
    ) = Wine(
        producer = producer,
        name = name,
        grapes = if (grape.isBlank()) emptyList() else listOf(grape),
        vintage = vintage,
        type = type,
        quantity = quantity,
        price = price,
        currency = currency,
        locationId = locationId,
        slot = slot
    )

    private fun cellar(wines: List<Wine>) = CellarData(wines = wines, locations = listOf(bar, rack))

    @Test
    fun aChangeSelectsTheSameBottlesAMoveWould() {
        val wines = listOf(bottle(locationId = null), bottle(locationId = "bar"), bottle(quantity = 0))
        val plan = BottleUpdate(producer = "Prospice", fromLocation = "unassigned", price = 48.0)
            .plan(cellar(wines))
        assertEquals(1, plan?.wines?.size)
        assertEquals("Only bottles with no location", plan?.filterLabel)
    }

    @Test
    fun aChangeThatChangesNothingIsRefused() {
        assertNull(BottleUpdate(producer = "Prospice").plan(cellar(listOf(bottle()))))
    }

    @Test
    fun aChangeWithNothingToMatchOnIsRefused() {
        assertNull(BottleUpdate(price = 48.0).plan(cellar(listOf(bottle()))))
    }

    @Test
    fun aNegativePriceOrCountIsRefusedWholeRatherThanDropped() {
        val wines = cellar(listOf(bottle()))
        assertNull(BottleUpdate(producer = "Prospice", price = -1.0).plan(wines))
        assertNull(BottleUpdate(producer = "Prospice", quantity = -2).plan(wines))
    }

    @Test
    fun aYearThatIsNotAYearIsRefused() {
        assertNull(BottleUpdate(producer = "Prospice", vintage = 12).plan(cellar(listOf(bottle()))))
        assertEquals(1, BottleUpdate(producer = "Prospice", vintage = 2018).plan(cellar(listOf(bottle())))?.wines?.size)
    }

    @Test
    fun theOldValueIsNamedWhenEveryBottleAgreesOnIt() {
        val plan = BottleUpdate(producer = "Prospice", quantity = 3, price = 48.0)
            .plan(cellar(listOf(bottle(), bottle())))
        assertEquals(listOf("Price → USD 48.00 (was USD 55.00)", "Bottles → 3 (was 2)"), plan?.lines)
    }

    @Test
    fun theOldValueIsLeftOutWhenTheBottlesDisagree() {
        val wines = listOf(bottle(price = 55.0), bottle(price = 60.0))
        val plan = BottleUpdate(producer = "Prospice", price = 48.0).plan(cellar(wines))
        assertEquals(listOf("Price → USD 48.00"), plan?.lines)
    }

    @Test
    fun aBlankSlotClearsTheShelfAndSaysWhatWasThere() {
        val wines = listOf(bottle(slot = "Top shelf"), bottle(slot = "Top shelf"))
        val plan = BottleUpdate(producer = "Prospice", slot = "").plan(cellar(wines))
        assertEquals(listOf("Slot cleared (was Top shelf)"), plan?.lines)
    }

    @Test
    fun aSlotAndAVintageAreWordedAsWrites() {
        val plan = BottleUpdate(producer = "Prospice", slot = "Top shelf", vintage = 2020)
            .plan(cellar(listOf(bottle(vintage = 2019))))
        assertEquals(listOf("Slot → Top shelf", "Vintage → 2020 (was 2019)"), plan?.lines)
    }

    @Test
    fun aNoteIsWordedAsAnAdditionToWhatIsThere() {
        val plan = BottleUpdate(producer = "Prospice", note = "Dusty, drink by 2028")
            .plan(cellar(listOf(bottle())))
        assertEquals(listOf("Add to my note: \u201cDusty, drink by 2028\u201d"), plan?.lines)
    }

    @Test
    fun thePlaceFilterAppliesToAChange() {
        val wines = listOf(bottle(locationId = null), bottle(locationId = "bar"), bottle(locationId = "rack"))
        val plan = BottleUpdate(producer = "Prospice", fromLocation = "Main rack", quantity = 1)
            .plan(cellar(wines))
        assertEquals(1, plan?.wines?.size)
        assertEquals("rack", plan?.wines?.single()?.locationId)
    }

    @Test
    fun aParsedLineReachesTheBottlesItNamed() {
        val reply = ProposalParsing.split(
            "The app will match them.\n" +
                "UPDATE {\"producer\":\"Prospice\",\"fromLocation\":\"unassigned\",\"price\":48}"
        )
        val wines = listOf(bottle(locationId = null), bottle(locationId = "bar"))
        val plan = reply.update?.plan(cellar(wines))
        assertEquals(1, plan?.wines?.size)
        assertEquals(48.0, reply.update?.price)
        assertTrue(plan?.lines?.single()?.startsWith("Price → USD 48.00") == true)
    }
}
