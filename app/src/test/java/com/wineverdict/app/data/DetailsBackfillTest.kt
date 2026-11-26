package com.wineverdict.app.data

import com.wineverdict.app.ai.AiException
import com.wineverdict.app.ai.SourcedNotes
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsBackfillTest {

    private fun wine(
        id: String,
        alcohol: Double? = null,
        notes: String = "",
        notesChecked: Long = 0L,
        abvChecked: Long = 0L,
        windowChecked: Long = 0L,
        placeChecked: Long = 0L,
        grapesChecked: Long = 0L,
        from: Int? = null,
        to: Int? = null,
        region: String = "",
        country: String = "",
        grapes: List<String> = emptyList(),
        name: String = "Syrah",
        producer: String = "Test Cellar"
    ) = Wine(
        id = id,
        producer = producer,
        name = name,
        alcohol = alcohol,
        expertNotes = notes,
        alcoholCheckedAt = abvChecked,
        notesCheckedAt = notesChecked,
        windowCheckedAt = windowChecked,
        placeCheckedAt = placeChecked,
        grapesCheckedAt = grapesChecked,
        drinkFrom = from,
        drinkTo = to,
        region = region,
        country = country,
        grapes = grapes,
        quantity = 1
    )

    /** A bottle every lookup has answered for, with every fact the job fills already on it. */
    private fun complete(id: String) = wine(
        id,
        alcohol = 13.5,
        notes = "Peppery and savory, cool-site fruit.",
        notesChecked = 5_000,
        abvChecked = 5_000,
        windowChecked = 5_000,
        placeChecked = 5_000,
        grapesChecked = 5_000,
        from = 2026,
        to = 2036,
        region = "Northern Rhône",
        country = "France",
        grapes = listOf("Syrah")
    )

    private fun run(
        wines: List<Wine>,
        lookup: suspend (Wine) -> SourcedNotes,
        saved: MutableMap<String, SourcedNotes> = mutableMapOf(),
        workers: Int = 1
    ): DetailsBackfill {
        val job = DetailsBackfill(
            wines = { wines },
            lookup = lookup,
            save = { id, _, result -> saved[id] = result },
            scope = CoroutineScope(Dispatchers.Unconfined),
            workers = workers
        )
        job.start()
        return job
    }

    @Test
    fun pendingIsOneQueueNotTwo() {
        val pending = DetailsBackfill.pending(
            listOf(
                wine("unread"),                                  // nothing checked yet
                wine("notes-only", notes = "A published note.", notesChecked = 5_000),
                complete("done"),                                // every fact, every stamp
                wine(
                    "missed", notes = "A published note.", notesChecked = 5_000, abvChecked = 5_000,
                    windowChecked = 5_000, placeChecked = 5_000, grapesChecked = 5_000
                ),
                wine("nameless", name = "", producer = "")
            )
        )
        assertEquals(listOf("unread", "notes-only"), pending.map { it.id })
    }

    @Test
    fun aBottleWithNotesAndAnAbvIsStillPendingForItsWindow() {
        val bottle = wine(
            "a", alcohol = 13.8, notes = "A published note.",
            notesChecked = 5_000, abvChecked = 5_000
        )
        assertEquals(listOf("window", "place", "grapes"), DetailsBackfill.missing(bottle))
        assertEquals(listOf("a"), DetailsBackfill.pending(listOf(bottle)).map { it.id })
    }

    @Test
    fun aFactThePagesNeverPrintedIsNotAskedForTwiceUnlessTheRunSaysAgain() {
        val asked = wine(
            "a", alcohol = 13.8, notes = "A published note.", notesChecked = 5_000,
            abvChecked = 5_000, windowChecked = 5_000, placeChecked = 5_000, grapesChecked = 5_000
        )
        assertEquals(emptyList<String>(), DetailsBackfill.pending(listOf(asked)).map { it.id })
        assertEquals(listOf("a"), DetailsBackfill.pending(listOf(asked), again = true).map { it.id })
        assertEquals(listOf("a"), DetailsBackfill.stillMissing(listOf(asked)).map { it.id })
    }

    @Test
    fun oneLookupFillsEveryFactAtOnce() {
        val saved = mutableMapOf<String, SourcedNotes>()
        val job = run(
            wines = listOf(wine("a")),
            lookup = {
                SourcedNotes(
                    notes = "Peppery and savory, cool-site fruit.",
                    tags = listOf("peppery"),
                    sources = listOf("https://example.com/a"),
                    alcohol = 13.8,
                    drinkFrom = 2026,
                    drinkTo = 2036,
                    country = "France",
                    region = "Northern Rhône",
                    grapes = listOf("Syrah")
                )
            },
            saved = saved
        )
        val progress = job.progress.value
        assertFalse(progress.running)
        assertEquals(1, progress.done)
        assertEquals(1, progress.notes)
        assertEquals(1, progress.abv)
        assertEquals(1, progress.windows)
        assertEquals(1, progress.place)
        assertEquals(1, progress.grapes)
        assertEquals(2036, saved.getValue("a").drinkTo)
    }

    @Test
    fun aFactTheRecordAlreadyHoldsIsNotCountedAsGained() {
        val job = run(
            wines = listOf(
                wine(
                    "a", alcohol = 14.0, from = 2024, to = 2030,
                    region = "Rhône", grapes = listOf("Syrah")
                )
            ),
            lookup = {
                SourcedNotes(
                    notes = "Peppery and savory.",
                    tags = listOf("peppery"),
                    sources = emptyList(),
                    alcohol = 13.8,
                    drinkFrom = 2026,
                    drinkTo = 2036,
                    region = "Northern Rhône",
                    grapes = listOf("Grenache")
                )
            }
        )
        assertEquals(0, job.progress.value.abv)
        assertEquals(0, job.progress.value.windows)
        assertEquals(0, job.progress.value.place)
        assertEquals(0, job.progress.value.grapes)
        assertEquals(1, job.progress.value.done)
    }

    @Test
    fun aPrintedWindowIsSavedAndAPlausibleNoiseIsNot() {
        assertEquals(2026 to 2036, DetailsBackfill.acceptableWindow(2026, 2036))
        assertEquals(null to null, DetailsBackfill.acceptableWindow(2036, 2026))
        assertEquals(null to null, DetailsBackfill.acceptableWindow(1900, 1905))
        assertEquals(2028 to null, DetailsBackfill.acceptableWindow(2028, null))
    }

    @Test
    fun onlyASanePrintedNumberIsSaved() {
        assertEquals(14.2, DetailsBackfill.acceptable(14.2)!!, 0.001)
        assertEquals(14.0, DetailsBackfill.acceptable(13.96)!!, 0.001)
        assertNull(DetailsBackfill.acceptable(null))
        assertNull(DetailsBackfill.acceptable(4.9))
        assertNull(DetailsBackfill.acceptable(25.1))
        assertNull(DetailsBackfill.acceptable(Double.NaN))
    }

    @Test
    fun aLookupThatFailsIsNotStampedAndIsAskedAgain() {
        var calls = 0
        var written = 0
        val wines = listOf(wine("a"))
        val job = DetailsBackfill(
            wines = { wines },
            lookup = {
                calls++
                throw AiException("The web search did not answer.")
            },
            save = { _, _, _ -> written++ },
            scope = CoroutineScope(Dispatchers.Unconfined),
            workers = 1
        )

        job.start()

        assertEquals(1, calls)
        assertEquals(0, written)
        assertEquals(1, job.progress.value.failed)
        assertEquals(0, job.progress.value.done)
        assertTrue(job.progress.value.message!!.contains("asked again"))

        // Nothing was saved, so the bottle is still pending and a second run asks it again.
        job.start()
        assertEquals(2, calls)
    }

    @Test
    fun threeFailuresInARowEndTheRunInsteadOfSpendingTheQueue() {
        var calls = 0
        val wines = (1..6).map { wine("w$it") }
        val job = run(
            wines = wines,
            lookup = {
                calls++
                throw AiException("Still nothing.")
            }
        )
        assertEquals(3, calls)
        assertEquals(3, job.progress.value.failed)
        assertEquals(0, job.progress.value.done)
    }

    @Test
    fun severalLookupsInFlightStillCountEveryBottleOnce() {
        val saved = mutableMapOf<String, SourcedNotes>()
        val wines = (1..4).map { wine("w$it") }
        val job = run(
            wines = wines,
            lookup = { w ->
                SourcedNotes(
                    "Some published note about ${w.id}.", emptyList(), emptyList(), alcohol = 13.0
                )
            },
            saved = saved,
            workers = 3
        )
        assertEquals(4, job.progress.value.done)
        assertEquals(4, saved.size)
    }
}
