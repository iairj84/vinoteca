package com.wineverdict.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The order the app looks for a place in.
 *
 * Saved circles before every lookup, names before addresses, and the user's own typing after both —
 * the rule the feature is, checked without a phone, a network or a bar.
 */
class PlaceFinderTest {

    private val here = LatLng(45.486919, -122.8058156)

    private val home = Place(
        id = "home", name = "Home", lat = 45.486919, lng = -122.8058156, radiusM = 45
    )
    private val elsewhere = Place(
        id = "elsewhere", name = "Mum's", lat = 45.60, lng = -122.70, radiusM = 45
    )

    private class FakeLookup(
        private val found: List<PlaceHit> = emptyList(),
        private val business: PlaceHit? = null,
        private val broken: Boolean = false
    ) : PlaceLookup {
        var searches = 0
            private set

        override suspend fun search(query: String, near: LatLng?, limit: Int): List<PlaceHit> {
            searches++
            if (broken) throw IOException("throttled")
            return found
        }

        override suspend fun reverse(lat: Double, lng: Double): PlaceHit? {
            if (broken) throw IOException("throttled")
            return business
        }
    }

    private class FakeAddress(private val hit: PlaceHit?) : AddressFinder {
        var asked = 0
            private set

        override suspend fun find(query: String, near: LatLng?): PlaceHit? {
            asked++
            return hit
        }
    }

    private val loyalLegion = PlaceHit(
        name = "Loyal Legion", city = "Beaverton", street = "4500 Southwest Watson Avenue",
        lat = 45.486919, lng = -122.8058156
    )

    @Test
    fun aSavedPlaceIsOfferedBeforeAnythingLookedUp() = runBlocking {
        val finder = PlaceFinder(
            FakeLookup(found = listOf(PlaceHit(name = "Homewood Suites", city = "Beaverton"))),
            FakeAddress(null)
        ) { listOf(home) }

        val hits = finder.suggestions("Hom", here)
        assertEquals("Home", hits.first().label)
    }

    @Test
    fun theNamesComeBackWithTheirTown() = runBlocking {
        val finder = PlaceFinder(FakeLookup(found = listOf(loyalLegion)), FakeAddress(null)) { emptyList() }
        val hits = finder.suggestions("Loyal L", here)
        assertEquals(listOf("Loyal Legion — Beaverton"), hits.map { it.label })
    }

    @Test
    fun anAddressIsTheAnswerWhenThereAreNoNames() = runBlocking {
        val address = PlaceHit(name = "Via Stella, 22, 41121 Modena MO, Italy", lat = 44.64, lng = 10.92)
        val finder = PlaceFinder(FakeLookup(), FakeAddress(address)) { emptyList() }
        assertEquals(listOf(address.label), finder.suggestions("Osteria Francescana", here).map { it.label })
    }

    @Test
    fun aThrottledLookupDoesNotLeaveTheBoxEmpty() = runBlocking {
        val address = PlaceHit(name = "155 W 51st St, New York", lat = 40.76, lng = -73.98)
        val finder = PlaceFinder(FakeLookup(broken = true), FakeAddress(address)) { emptyList() }
        assertEquals(1, finder.suggestions("Le Bernardin", here).size)
    }

    @Test
    fun theAddressIsNotAskedWhenTheNamesAnswered() = runBlocking {
        val addresses = FakeAddress(PlaceHit(name = "should not appear"))
        val finder = PlaceFinder(FakeLookup(found = listOf(loyalLegion)), addresses) { emptyList() }
        finder.suggestions("Loyal L", here)
        assertEquals(0, addresses.asked)
    }

    @Test
    fun oneWordIsNotEnoughToSearchOn() = runBlocking {
        val lookup = FakeLookup(found = listOf(loyalLegion))
        val finder = PlaceFinder(lookup, FakeAddress(null)) { emptyList() }
        assertTrue(finder.suggestions("L", here).isEmpty())
        assertEquals(0, lookup.searches)
    }

    @Test
    fun fourRowsIsTheWholeDropdown() = runBlocking {
        val many = (1..9).map { PlaceHit(name = "Loyal Legion $it", city = "Beaverton") }
        val finder = PlaceFinder(FakeLookup(found = many), FakeAddress(null)) { emptyList() }
        assertEquals(4, finder.suggestions("Loyal", here).size)
    }

    @Test
    fun aSavedCircleAnswersTheIconBeforeAnyLookup() = runBlocking {
        val finder = PlaceFinder(
            FakeLookup(business = loyalLegion),
            FakeAddress(null)
        ) { listOf(home) }
        val answer = finder.here(here.lat, here.lng)
        assertTrue(answer is HereResult.Saved)
        assertEquals("Home", (answer as HereResult.Saved).place.name)
    }

    @Test
    fun theNearestSavedCircleWins() = runBlocking {
        val wider = home.copy(id = "wide", name = "The block", radiusM = 300)
        val finder = PlaceFinder(FakeLookup(), FakeAddress(null)) { listOf(wider, home) }
        assertEquals("Home", (finder.here(here.lat, here.lng) as HereResult.Saved).place.name)
    }

    @Test
    fun aBusinessIsNamedWhenNoCircleCoversTheFix() = runBlocking {
        val finder = PlaceFinder(FakeLookup(business = loyalLegion), FakeAddress(null)) { listOf(elsewhere) }
        val answer = finder.here(here.lat, here.lng)
        assertTrue(answer is HereResult.Business)
        assertEquals("Loyal Legion — Beaverton", (answer as HereResult.Business).hit.label)
    }

    @Test
    fun aPlainHouseSaysNothingRatherThanAnAddress() = runBlocking {
        val finder = PlaceFinder(FakeLookup(business = null), FakeAddress(null)) { listOf(elsewhere) }
        assertEquals(HereResult.NothingKnown, finder.here(here.lat, here.lng))
    }

    @Test
    fun noFixIsSaidPlainly() = runBlocking {
        val finder = PlaceFinder(FakeLookup(), FakeAddress(null)) { listOf(home) }
        assertEquals(HereResult.NoFix, finder.here(null, null))
    }
}
