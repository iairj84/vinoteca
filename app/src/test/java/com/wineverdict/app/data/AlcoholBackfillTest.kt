package com.wineverdict.app.data

import com.wineverdict.app.ai.SourcedNotes
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AlcoholBackfillTest {

    private fun wine(
        id: String,
        alcohol: Double? = null,
        checked: Long = 0L,
        name: String = "Syrah",
        producer: String = "Test Cellar"
    ) = Wine(
        id = id,
        producer = producer,
        name = name,
        alcohol = alcohol,
        alcoholCheckedAt = checked,
        quantity = 1
    )

    @Test
    fun onlyBottlesThatNeedANumberAndHaveNotBeenAsked() {
        val pending = AlcoholBackfill.pending(
            listOf(
                wine("a"),
                wine("b", alcohol = 14.2),
                wine("c", checked = 1_700_000_000_000),
                wine("d", name = "", producer = "")
            )
        )
        assertEquals(listOf("a"), pending.map { it.id })
    }

    @Test
    fun onlyASanePrintedNumberIsSaved() {
        assertEquals(14.2, AlcoholBackfill.acceptable(14.2)!!, 0.001)
        assertEquals(14.0, AlcoholBackfill.acceptable(13.96)!!, 0.001)
        assertNull(AlcoholBackfill.acceptable(null))
        assertNull(AlcoholBackfill.acceptable(4.9))
        assertNull(AlcoholBackfill.acceptable(25.1))
        assertNull(AlcoholBackfill.acceptable(Double.NaN))
    }

    @Test
    fun theRunSavesWhatWasFoundAndStampsTheMisses() {
        val saved = mutableMapOf<String, Double?>()
        val wines = listOf(wine("a"), wine("b"))
        val job = AlcoholBackfill(
            wines = { wines },
            lookup = { w ->
                if (w.id == "a") {
                    SourcedNotes(notes = "", tags = emptyList(), sources = emptyList(), alcohol = 14.2)
                } else {
                    SourcedNotes(notes = "", tags = emptyList(), sources = emptyList())
                }
            },
            save = { id, abv, _ -> saved[id] = abv },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        job.start()

        assertEquals(mapOf("a" to 14.2, "b" to null), saved)
        val progress = job.progress.value
        assertFalse(progress.running)
        assertEquals(2, progress.done)
        assertEquals(1, progress.found)
        assertEquals(1, progress.missed)
    }

    @Test
    fun aBadNumberIsStoredAsAMissRatherThanAsAFact() {
        val saved = mutableMapOf<String, Double?>()
        val job = AlcoholBackfill(
            wines = { listOf(wine("a")) },
            lookup = { SourcedNotes("", emptyList(), emptyList(), alcohol = 40.0) },
            save = { id, abv, _ -> saved[id] = abv },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        job.start()

        assertEquals(mapOf("a" to null), saved)
        assertEquals(1, job.progress.value.missed)
        assertEquals(0, job.progress.value.found)
    }
}
