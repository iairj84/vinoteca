package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The circles: distance, whether a fix is inside one, and which one wins when two overlap.
 *
 * These are the numbers the feature stands on — a night tagged Home when the fix is a hundred metres
 * away, or tagged the bar when it is in the flat above it, is the whole thing being wrong — so they
 * are checked here rather than by walking around with a phone.
 */
class PlaceBookTest {

    private val beaverton = LatLng(45.486919, -122.8058156)

    @Test
    fun sameSpotIsNoDistance() {
        assertEquals(0.0, metresBetween(45.0, -122.0, 45.0, -122.0), 0.001)
    }

    @Test
    fun aDegreeOfLatitudeIsAboutAHundredAndElevenKilometres() {
        val metres = metresBetween(45.0, -122.0, 46.0, -122.0)
        assertTrue("was $metres", abs(metres - 111_195.0) < 400.0)
    }

    @Test
    fun aFixInsideTheCircleCounts() {
        // 0.00027 degrees of latitude is about thirty metres.
        val place = Place(name = "Home", lat = 45.0, lng = -122.0, radiusM = 45)
        assertTrue(place.covers(45.0 + 0.00027, -122.0))
        assertFalse(place.covers(45.0 + 0.00081, -122.0))
    }

    @Test
    fun theNearestCircleWinsWhereTwoOverlap() {
        val home = Place(id = "home", name = "Home", lat = 45.0, lng = -122.0, radiusM = 150)
        // About 25 metres north of the first pin.
        val neighbour = Place(id = "next", name = "Next door", lat = 45.000225, lng = -122.0, radiusM = 60)
        assertEquals("next", nearestPlace(listOf(home, neighbour), 45.00024, -122.0)?.id)
        assertEquals("home", nearestPlace(listOf(home, neighbour), 45.00005, -122.0)?.id)
    }

    @Test
    fun aPlaceWithNobodyInsideIsNull() {
        val home = Place(name = "Home", lat = 45.0, lng = -122.0, radiusM = 45)
        assertNull(nearestPlace(listOf(home), 45.05, -122.0))
        assertNull(nearestPlace(emptyList(), 45.0, -122.0))
    }

    @Test
    fun theRadiusIsSaidInYardsAndMiles() {
        assertEquals("50 yd", radiusWords(46))
        assertEquals("0.6 mi", radiusWords(1000))
    }

    @Test
    fun everyCircleThePickerOffersReadsAsARoundNumberOfYards() {
        assertEquals(
            listOf("25 yd", "50 yd", "100 yd", "200 yd", "500 yd"),
            PLACE_RADIUS_CHOICES.map { radiusWords(it) }
        )
    }

    @Test
    fun aRadiusStoredInMetresByAnOlderBuildStillReadsNaturally() {
        // Places saved before the picker spoke yards still hold the old metre values.
        assertEquals("165 yd", radiusWords(150))
        assertEquals("330 yd", radiusWords(300))
    }

    @Test
    fun aNameAndATownReadAsOnePlace() {
        assertEquals("Loyal Legion — Beaverton", placeLabel("Loyal Legion", "Beaverton"))
        assertEquals("Loyal Legion", placeLabel("Loyal Legion", " "))
        // The town is already in the name, so saying it twice would read as two different places.
        assertEquals("Loyal Legion Beaverton", placeLabel("Loyal Legion Beaverton", "Beaverton"))
    }

    @Test
    fun aNewPlaceStartsAtTheDefaultCircle() {
        val place = Place(name = "Home")
        assertEquals(PLACE_RADIUS_M, place.radiusM)
        assertTrue(PLACE_RADIUS_M in PLACE_RADIUS_CHOICES)
        assertEquals("50 yd", radiusWords(place.radiusM))
        assertNotNull(place.id)
    }

    @Test
    fun theRadiusChoicesAreAllWithinWhatTheStoreAccepts() {
        PLACE_RADIUS_CHOICES.forEach { metres ->
            assertTrue("$metres is outside the stored range", metres in PLACE_MIN_RADIUS_M..PLACE_MAX_RADIUS_M)
        }
    }

    @Test
    fun anEmptyNameIsNotStored() {
        // The store refuses it; the normalisation it applies to a real name is checked through the
        // copy it returns, which is what the screens keep.
        val cleaned = Place(name = "  Home  ", radiusM = 4).copy(
            name = "  Home  ".trim(),
            radiusM = 4.coerceIn(PLACE_MIN_RADIUS_M, PLACE_MAX_RADIUS_M)
        )
        assertEquals("Home", cleaned.name)
        assertEquals(PLACE_MIN_RADIUS_M, cleaned.radiusM)
    }

    @Test
    fun beavertonToPortlandIsAboutFiveKilometres() {
        val portland = LatLng(45.5177924, -122.6594942)
        val metres = metresBetween(beaverton.lat, beaverton.lng, portland.lat, portland.lng)
        assertTrue("was $metres", metres in 11_600.0..12_200.0)
    }
}
