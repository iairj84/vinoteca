package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which bottles the picture job is for: the ones with no photograph, and only those that can
 * actually be searched for. A record naming no wine is not a bottle to find a picture of.
 */
class PhotoBackfillTest {

    @Test
    fun aBottleWithNoPictureIsPending() {
        val wines = listOf(Wine(producer = "Stoller", name = "Dundee Hills"))
        assertEquals(1, PhotoBackfill.pending(wines).size)
    }

    @Test
    fun aBottleWithAPictureIsNotAskedForAnother() {
        val wines = listOf(
            Wine(producer = "Stoller", name = "Dundee Hills"),
            Wine(producer = "Ridge", name = "Geyserville", photo = "abc.jpg")
        )
        val pending = PhotoBackfill.pending(wines)
        assertEquals(1, pending.size)
        assertEquals("Stoller", pending.first().producer)
    }

    @Test
    fun aRecordThatNamesNoWineIsNotSearchedFor() {
        val wines = listOf(Wine(producer = "", name = ""))
        assertEquals(0, PhotoBackfill.pending(wines).size)
    }

    @Test
    fun aProducerOrANameAloneIsEnoughToSearchFor() {
        val wines = listOf(
            Wine(producer = "Ridge"),
            Wine(name = "Geyserville")
        )
        assertEquals(2, PhotoBackfill.pending(wines).size)
    }

    /**
     * The check pass: every kept picture measured first, the searches only where one fails, and the
     * two pictures treated differently — a page's may be taken off, the user's own may not.
     */
    private class Fakes(
        val wines: List<Wine>,
        val kept: ByteArray? = ByteArray(4),
        val looksLikeTheBottle: Boolean = true,
        val found: ByteArray? = ByteArray(4),
        val dropFails: Boolean = false
    ) {
        var lookups = 0
        val saved = mutableListOf<Pair<String, ByteArray>>()
        val dropped = mutableListOf<String>()

        fun job() = PhotoBackfill(
            wines = { wines },
            find = { lookups += 1; found },
            save = { id, bytes -> saved += id to bytes },
            scope = CoroutineScope(Dispatchers.Default),
            pauseMillis = 0,
            stored = { kept },
            looksLikeTheBottle = { looksLikeTheBottle },
            fromThePages = { CellarRepository.isWebPhoto(it.photo) },
            drop = {
                if (dropFails) throw IllegalStateException("no") else dropped += it
            }
        )

        /** Starts the check and waits for it to finish: the job runs on its own dispatcher. */
        fun run(backfill: PhotoBackfill): PhotoBackfill.Progress {
            backfill.check()
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                val now = backfill.progress.value
                if (!now.running && now.done > 0) return now
                Thread.sleep(5)
            }
            return backfill.progress.value
        }
    }

    @Test
    fun aPictureThatAlreadyShowsTheBottleIsNotSearchedFor() {
        val fakes = Fakes(
            wines = listOf(Wine(producer = "Ridge", name = "Geyserville", photo = "web-1.jpg"))
        )
        val end = fakes.run(fakes.job())
        assertEquals(0, fakes.lookups)
        assertEquals(1, end.sound)
        assertEquals(0, end.found)
        assertEquals(0, end.emptied)
    }

    @Test
    fun aPagePictureThatIsNotTheBottleIsReplaced() {
        val fakes = Fakes(
            wines = listOf(Wine(producer = "Prospice", name = "Resurgent", photo = "web-2.jpg")),
            looksLikeTheBottle = false,
            found = ByteArray(7)
        )
        val end = fakes.run(fakes.job())
        assertEquals(1, fakes.lookups)
        assertEquals(1, fakes.saved.size)
        assertEquals(fakes.wines.single().id, fakes.saved.single().first)
        assertEquals(1, end.found)
        assertEquals(0, end.emptied)
        assertEquals(emptyList<String>(), fakes.dropped)
    }

    @Test
    fun aPagePictureWithNothingBetterIsTakenOffRatherThanKept() {
        val fakes = Fakes(
            wines = listOf(Wine(producer = "Prospice", name = "Resurgent", photo = "web-3.jpg")),
            looksLikeTheBottle = false,
            found = null
        )
        val end = fakes.run(fakes.job())
        assertEquals(listOf(fakes.wines.single().id), fakes.dropped)
        assertEquals(1, end.emptied)
        assertEquals(0, end.left)
        assertEquals(0, end.found)
    }

    @Test
    fun aPictureTheUserTookItselfIsNeverJustDeleted() {
        val fakes = Fakes(
            wines = listOf(Wine(producer = "Ridge", name = "Geyserville", photo = "abc.jpg")),
            looksLikeTheBottle = false,
            found = null
        )
        val end = fakes.run(fakes.job())
        assertEquals(emptyList<String>(), fakes.dropped)
        assertEquals(1, end.left)
        assertEquals(0, end.emptied)
    }

    @Test
    fun aRecordWhoseFileIsGoneIsCountedNotSearchedFor() {
        val fakes = Fakes(
            wines = listOf(Wine(producer = "Ridge", name = "Geyserville", photo = "web-4.jpg")),
            kept = null
        )
        val end = fakes.run(fakes.job())
        assertEquals(0, fakes.lookups)
        assertEquals(1, end.failed)
        assertEquals(0, end.sound)
        assertEquals(emptyList<String>(), fakes.dropped)
    }

    @Test
    fun aWebPictureIsTheOnlyKindTheCheckMayTakeOff() {
        assertEquals(true, CellarRepository.isWebPhoto("web-abc.jpg"))
        assertEquals(false, CellarRepository.isWebPhoto("abc.jpg"))
    }
}