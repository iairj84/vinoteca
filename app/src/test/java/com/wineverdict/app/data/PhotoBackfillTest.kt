package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
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
}