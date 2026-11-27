package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as "picked that night". The card is a claim about the user's evening, so the advice's
 * own three suggestions must never be one of the signals.
 */
class PickedWinesTest {

    private fun wine(id: String, rating: Int? = null) =
        Wine(id = id, producer = "Producer $id", name = "Cuvée $id", rating = rating)

    @Test
    fun theAnswersSuggestionsAreNotPicks() {
        val list = SavedList(
            picks = listOf("a", "b", "c"),
            wines = listOf(wine("a"), wine("b"), wine("c"))
        )

        assertEquals(emptyList<String>(), pickedWines(list, emptySet()))
    }

    @Test
    fun aVerdictOnTheMenuIsAPick() {
        val list = SavedList(wines = listOf(wine("a"), wine("b", Verdict.LOVED.rating)))

        assertEquals(listOf("b"), pickedWines(list, emptySet()))
    }

    @Test
    fun anOrderOrKeepAlreadyInTheCellarCountsOnce() {
        // Menu kept before picks were recorded: the only trace left is the cellar record the order
        // or the keep created, under the menu's own id.
        val list = SavedList(
            chosen = listOf("b"),
            wines = listOf(wine("a"), wine("b", Verdict.GOOD.rating), wine("c"))
        )

        assertEquals(listOf("b"), pickedWines(list, setOf("b")))
        assertEquals(2, pickedWines(list, setOf("c")).size)
        assertTrue(pickedWines(list, setOf("zzz")).contains("b"))
    }
}
