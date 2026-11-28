package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup that left the label pictures behind restored the facts of a cellar and not its record.
 * The names inside it also come from a file somebody else could have written, so they are checked.
 */
class BackupPhotosTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)

    private fun cellar() = CellarData(
        wines = listOf(Wine(id = "w1", name = "Barolo", quantity = 1, photo = "w1.jpg"))
    )

    @Test
    fun labelPicturesSurviveTheRoundTrip() {
        val text = Backup.encode(
            cellar = cellar(),
            taste = "",
            appVersion = "1.2.45",
            currency = "USD",
            photos = mapOf("w1.jpg" to jpeg)
        )

        val back = Backup.decode(text)

        assertEquals(3, back.version)
        assertArrayEquals(jpeg, Backup.photos(back)["w1.jpg"])
    }

    @Test
    fun aBackupWrittenBeforePicturesStillOpens() {
        val text = Backup.encode(cellar(), "", "1.2.44", "USD")

        val back = Backup.decode(text)

        assertTrue(back.photos.isEmpty())
        assertTrue(Backup.photos(back).isEmpty())
    }

    @Test
    fun aNameThatWouldLeaveTheAppFolderIsDropped() {
        val text = Backup.encode(
            cellar = cellar(),
            taste = "",
            appVersion = "1.2.45",
            currency = "USD",
            photos = mapOf(
                "../../shared_prefs/wineverdict_settings.xml" to jpeg,
                "..\\w1.jpg" to jpeg,
                "w 1.jpg" to jpeg,
                "w1.jpg" to jpeg
            )
        )

        assertEquals(setOf("w1.jpg"), Backup.photos(Backup.decode(text)).keys)
    }
}
