package com.wineverdict.app.data

import com.wineverdict.app.ai.SourcedNotes
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppliedDetailsTest {

    private val now = 1_700_000_000_000

    private fun plain() = Wine(producer = "Test Cellar", name = "Syrah", vintage = 2021)

    private fun everything() = SourcedNotes(
        notes = "Peppery and savory.",
        tags = listOf("peppery", "savory"),
        sources = listOf("https://example.com/a"),
        alcohol = 13.8,
        drinkFrom = 2026,
        drinkTo = 2036,
        country = "France",
        region = "Northern Rhône",
        appellation = "Crozes-Hermitage",
        grapes = listOf("Syrah")
    )

    @Test
    fun oneAnswerWritesEveryFactAndStampsEveryQuestion() {
        val after = plain().withDetails(everything(), 13.8, now)
        assertEquals(13.8, after.alcohol!!, 0.001)
        assertEquals("Peppery and savory.", after.expertNotes)
        assertEquals(listOf("peppery", "savory"), after.flavorTags)
        assertEquals("https://example.com/a", after.notesSource)
        assertEquals(2026, after.drinkFrom)
        assertEquals(2036, after.drinkTo)
        assertEquals("France", after.country)
        assertEquals("Northern Rhône", after.region)
        assertEquals("Crozes-Hermitage", after.appellation)
        assertEquals(listOf("Syrah"), after.grapes)
        assertEquals(now, after.notesCheckedAt)
        assertEquals(now, after.alcoholCheckedAt)
        assertEquals(now, after.windowCheckedAt)
        assertEquals(now, after.placeCheckedAt)
        assertEquals(now, after.grapesCheckedAt)
    }

    @Test
    fun anAnswerWithoutAFactLeavesTheRecordAloneAndStillStampsIt() {
        val before = Wine(
            producer = "Test Cellar", name = "Syrah", region = "Rhône", grapes = listOf("Syrah")
        )
        val after = before.withDetails(SourcedNotes("", emptyList(), emptyList()), null, now)
        assertEquals(before.alcohol, after.alcohol)
        assertEquals(before.region, after.region)
        assertEquals(before.grapes, after.grapes)
        assertEquals(before.drinkFrom, after.drinkFrom)
        // Asked, and no page gave one: stamped so the same miss is not asked again next pass.
        assertEquals(now, after.windowCheckedAt)
    }

    @Test
    fun aWindowTheUserEnteredIsNotHalfReplaced() {
        val owned = Wine(producer = "Test Cellar", name = "Syrah", drinkFrom = 2020, drinkTo = 2030)
        val after = owned.withDetails(everything(), 13.8, now)
        assertEquals(2020, after.drinkFrom)
        assertEquals(2030, after.drinkTo)
    }

    @Test
    fun halfAWindowCountsAsAWindow() {
        val owned = Wine(producer = "Test Cellar", name = "Syrah", drinkTo = 2030)
        val after = owned.withDetails(everything(), 13.8, now)
        assertNull(after.drinkFrom)
        assertEquals(2030, after.drinkTo)
    }

    @Test
    fun theLabelBeatsThePage() {
        val owned = Wine(
            producer = "Test Cellar",
            name = "Syrah",
            alcohol = 14.0,
            country = "Australia",
            region = "Barossa",
            grapes = listOf("Shiraz")
        )
        val after = owned.withDetails(everything(), 13.8, now)
        assertEquals(14.0, after.alcohol!!, 0.001)
        assertEquals("Australia", after.country)
        assertEquals("Barossa", after.region)
        assertEquals(listOf("Shiraz"), after.grapes)
    }
}
