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
        notesChecked: Long = 0L,
        abvChecked: Long = 0L,
        name: String = "Syrah",
        producer: String = "Test Cellar"
    ) = Wine(
        id = id,
        producer = producer,
        name = name,
        alcohol = alcohol,
        alcoholCheckedAt = abvChecked,
        notesCheckedAt = notesChecked,
        quantity = 1
    )

    private fun run(
        wines: List<Wine>,
        lookup: suspend (Wine) -> SourcedNotes,
        saved: MutableMap<String, Double?> = mutableMapOf(),
        workers: Int = 1
    ): DetailsBackfill {
        val job = DetailsBackfill(
            wines = { wines },
            lookup = lookup,
            save = { id, abv, _ -> saved[id] = abv },
            scope = CoroutineScope(Dispatchers.Unconfined),
            workers = workers
        )
        job.start()
        return job
    }

    private val none = SourcedNotes("", emptyList(), emptyList())

    @Test
    fun pendingIsOneQueueNotTwo() {
        val pending = DetailsBackfill.pending(
            listOf(
                wine("unread"),                                  // nothing checked yet
                wine("notes-only", notesChecked = 5_000),        // has notes, no ABV
                wine("done", alcohol = 14.2, notesChecked = 5_000, abvChecked = 5_000),
                wine("missed", notesChecked = 5_000, abvChecked = 5_000),
                wine("nameless", name = "", producer = "")
            )
        )
        assertEquals(listOf("unread", "notes-only"), pending.map { it.id })
    }

    @Test
    fun askingAgainPicksUpTheStampedMisses() {
        val stamped = wine("missed", notesChecked = 5_000, abvChecked = 5_000)
        val done = wine("done", alcohol = 13.5, notesChecked = 5_000, abvChecked = 5_000)
        assertEquals(emptyList<String>(), DetailsBackfill.pending(listOf(stamped, done)).map { it.id })
        assertEquals(listOf("missed"), DetailsBackfill.pending(listOf(stamped, done), again = true).map { it.id })
    }

    @Test
    fun oneLookupFillsBothTheNotesAndTheFigure() {
        val saved = mutableMapOf<String, Double?>()
        val job = run(
            wines = listOf(wine("a")),
            lookup = { SourcedNotes("Peppery and savory, cool-site fruit.", listOf("peppery"), emptyList(), alcohol = 13.8) },
            saved = saved
        )
        val progress = job.progress.value
        assertFalse(progress.running)
        assertEquals(1, progress.done)
        assertEquals(1, progress.notes)
        assertEquals(1, progress.abv)
        assertEquals(mapOf("a" to 13.8), saved)
    }

    @Test
    fun aBottleAlreadyCarryingAnAbvIsNotCountedAsGainingOne() {
        val job = run(
            wines = listOf(wine("a", alcohol = 14.0)),
            lookup = { SourcedNotes("Peppery and savory.", listOf("peppery"), emptyList(), alcohol = 13.8) }
        )
        assertEquals(0, job.progress.value.abv)
        assertEquals(1, job.progress.value.done)
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
        val saved = mutableMapOf<String, Double?>()
        val wines = (1..4).map { wine("w$it") }
        val job = run(
            wines = wines,
            lookup = { w -> SourcedNotes("Some published note about ${w.id}.", emptyList(), emptyList(), alcohol = 13.0) },
            saved = saved,
            workers = 3
        )
        assertEquals(4, job.progress.value.done)
        assertEquals(4, saved.size)
    }
}
