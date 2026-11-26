package com.wineverdict.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {

    private fun wine(rating: Int? = null, favorite: Boolean = false) =
        Wine(name = "Test bottle", rating = rating, favorite = favorite)

    @Test
    fun fourWordsInTheOrderTheScreenShowsThem() {
        assertEquals(
            listOf("Not for me", "Fine", "Good", "Loved"),
            Verdict.values().map { it.label }
        )
    }

    @Test
    fun aStoredTwoReadsAsNotForMe() {
        assertEquals(Verdict.NOT_FOR_ME, Verdict.of(2))
        assertEquals("Not for me", Verdict.label(1))
        assertNull(Verdict.of(null))
        assertNull(Verdict.of(0))
    }

    @Test
    fun goodAndLovedAreTheBottlesThePalateReads() {
        assertTrue(Verdict.liked(wine(rating = 4)))
        assertTrue(Verdict.liked(wine(rating = 5)))
        assertTrue(Verdict.liked(wine(favorite = true)))
        assertFalse(Verdict.liked(wine(rating = 3)))
        assertFalse(Verdict.liked(wine()))
    }

    @Test
    fun onlyNotForMeIsADislike() {
        assertTrue(Verdict.disliked(wine(rating = 1)))
        assertTrue(Verdict.disliked(wine(rating = 2)))
        assertFalse(Verdict.disliked(wine(rating = 3)))
        assertFalse(Verdict.disliked(wine()))
    }

    @Test
    fun aHeartWithNoWordStillShowsOne() {
        assertEquals("Loved", Verdict.labelFor(wine(favorite = true)))
        assertEquals("Fine", Verdict.labelFor(wine(rating = 3, favorite = true)))
        assertNull(Verdict.labelFor(wine()))
    }
}
