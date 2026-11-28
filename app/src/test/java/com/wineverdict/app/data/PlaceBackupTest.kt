package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saved place is a circle somebody drew on their own phone; nothing can look it up again. A backup
 * that carried the nights and not the names would cost every Home its name on a new phone.
 */
class PlaceBackupTest {

    @Test
    fun savedPlacesTravelInTheBackup() {
        val text = Backup.encode(
            cellar = CellarData(),
            taste = "my taste",
            appVersion = "1.2.54",
            currency = "USD",
            places = listOf(
                Place(id = "home", name = "Home", lat = 45.4869, lng = -122.8058, radiusM = 45),
                Place(id = "mums", name = "Mum's", lat = 45.6, lng = -122.7, radiusM = 150)
            )
        )

        val back = Backup.decode(text)

        assertEquals(2, back.places.size)
        assertEquals("Home", back.places.first().name)
        assertEquals(150, back.places.last().radiusM)
        assertEquals(45.4869, back.places.first().lat, 0.00001)
    }

    @Test
    fun aBackupWrittenBeforePlacesStillOpens() {
        val text = Backup.encode(
            cellar = CellarData(),
            taste = "my taste",
            appVersion = "1.2.53",
            currency = "USD"
        )

        assertTrue(Backup.decode(text).places.isEmpty())
    }
}
