package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which bottles the book offers to catch up with, and the date each one can honestly claim.
 *
 * The rule the tests pin down: a tasting is dated by its own note, a verdict alone by the bottle's
 * last change, and a bottle the book already has an entry for is left alone. An undated free-form
 * note with no verdict has no date to stand on and is not invented.
 */
class BottleTastingsTest {

    private val zone = ZoneId.systemDefault()

    private fun noon(day: String): Long =
        LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun wine(
        id: String,
        notes: String = "",
        rating: Int? = null,
        updatedAt: Long = 1_700_000_000_000,
        quantity: Int = 1
    ) = Wine(
        id = id,
        producer = "Test Producer",
        name = "Test Wine",
        vintage = 2020,
        quantity = quantity,
        myNotes = notes,
        rating = rating,
        updatedAt = updatedAt
    )

    @Test
    fun aDatedNoteIsADatedTasting() {
        val found = bottleTastings(
            listOf(wine("a", notes = "2026-11-12 — cherry, cold")),
            emptyList(),
            zone
        )

        assertEquals(1, found.size)
        assertEquals(noon("2026-11-12"), found.single().at)
        assertEquals("cherry, cold", found.single().note)
    }

    @Test
    fun aVerdictWithoutANoteUsesTheLastChange() {
        val found = bottleTastings(
            listOf(wine("a", rating = Verdict.LOVED.rating, updatedAt = 1_700_000_000_000)),
            emptyList(),
            zone
        )

        assertEquals(1, found.size)
        assertEquals(1_700_000_000_000, found.single().at)
        assertEquals("", found.single().note)
    }

    @Test
    fun aBottleAlreadyInTheBookIsLeftAlone() {
        val rated = wine("a", rating = Verdict.GOOD.rating)
        assertTrue(
            bottleTastings(listOf(rated), listOf(Drink(wineId = "a")), zone).isEmpty()
        )
    }

    @Test
    fun theNewestDatedNoteWins() {
        val found = bottleTastings(
            listOf(wine("a", notes = "2026-10-01 — first\n2026-11-12 — second")),
            emptyList(),
            zone
        )

        assertEquals("second", found.single().note)
        assertEquals(noon("2026-11-12"), found.single().at)
    }

    @Test
    fun anUndatedNoteWithoutAVerdictHasNoDateToStandOn() {
        // The user's own free-form note cannot be dated, and with no verdict either there is
        // nothing to date it by: it stays on the bottle rather than gain a made-up night.
        assertTrue(
            bottleTastings(
                listOf(wine("a", notes = "drank at the beach")),
                emptyList(),
                zone
            ).isEmpty()
        )
    }

    @Test
    fun aFinishedBottleStillComesAcross() {
        val found = bottleTastings(
            listOf(wine("a", rating = Verdict.FINE.rating, quantity = 0)),
            emptyList(),
            zone
        )

        assertEquals(1, found.size)
    }

    @Test
    fun twoBottlesComeAcrossOldestFirst() {
        val found = bottleTastings(
            listOf(
                wine("new", notes = "2026-11-12 — later"),
                wine("old", notes = "2026-11-01 — earlier")
            ),
            emptyList(),
            zone
        )

        assertEquals(listOf("old", "new"), found.map { it.wine.id })
    }
}
