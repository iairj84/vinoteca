package com.wineverdict.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reverse lookup behind the place question's region step.
 *
 * Photon first because it answers town, state and street in one shape; the platform geocoder second
 * because a live emulator resolved Nominatim and could not resolve Photon's host at all, and the
 * region step then had nothing to read. A coordinate neither service knows is null, not a guess.
 */
class TownAtTest {

    private class Lookup(private val hit: PlaceHit?) : PlaceLookup {
        var calls = 0
        override suspend fun search(query: String, near: LatLng?, limit: Int): List<PlaceHit> = emptyList()
        override suspend fun reverse(lat: Double, lng: Double): PlaceHit? {
            calls++
            return hit
        }
    }

    private class Geocoder(private val hit: PlaceHit?) : AddressFinder {
        var calls = 0
        override suspend fun find(query: String, near: LatLng?): PlaceHit? = null
        override suspend fun reverse(lat: Double, lng: Double): PlaceHit? {
            calls++
            return hit
        }
    }

    @Test
    fun photonAnswersFirst() = runBlocking {
        val photon = Lookup(PlaceHit("Portland", city = "Portland", region = "Oregon"))
        val geocoder = Geocoder(PlaceHit("Portland", city = "Portland", region = "Maine"))
        val finder = PlaceFinder(photon, geocoder) { emptyList() }

        val hit = finder.townAt(45.5231, -122.6765)
        assertEquals("Oregon", hit?.region)
        assertEquals(0, geocoder.calls)
    }

    @Test
    fun aSilentPhotonFallsBackToThePhonesGeocoder() = runBlocking {
        // The live case: photon.komoot.io did not resolve, the platform still knew the address.
        val photon = Lookup(null)
        val geocoder = Geocoder(PlaceHit("Portland", city = "Portland", region = "Oregon"))
        val finder = PlaceFinder(photon, geocoder) { emptyList() }

        val hit = finder.townAt(45.5231, -122.6765)
        assertEquals("Portland", hit?.city)
        assertEquals("Oregon", hit?.region)
        assertEquals(1, photon.calls)
        assertEquals(1, geocoder.calls)
    }

    @Test
    fun neitherKnowingIsNullRatherThanAPlace() = runBlocking {
        val finder = PlaceFinder(Lookup(null), Geocoder(null)) { emptyList() }
        assertNull(finder.townAt(0.0, 0.0))
    }
}
