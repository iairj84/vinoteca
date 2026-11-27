package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cellar book is the user's own record, so it has to leave the phone with the rest of it. The
 * format is additive: a file written before the book existed carries no drinks and must still open.
 */
class BackupDrinksTest {

    private fun night() = Drink(
        id = "d1",
        wineId = "w1",
        title = "2019 Barolo",
        type = "Red",
        region = "Piedmont",
        rating = 5,
        note = "tar and roses",
        flavors = listOf("Cherry", "Earth"),
        again = true,
        at = 1_700_000_000_000L,
        via = Drink.MANUAL
    )

    @Test
    fun theCellarBookSurvivesTheRoundTrip() {
        val text = Backup.encode(
            cellar = CellarData(wines = listOf(Wine(id = "w1", name = "Barolo", quantity = 0))),
            taste = "",
            appVersion = "1.2.41",
            currency = "USD",
            drinks = listOf(night())
        )

        val back = Backup.decode(text)

        assertEquals(1, back.drinks.size)
        val drink = back.drinks.first()
        assertEquals("2019 Barolo", drink.title)
        assertEquals(listOf("Cherry", "Earth"), drink.flavors)
        assertEquals(true, drink.again)
        assertEquals(Drink.MANUAL, drink.via)
        assertEquals(1_700_000_000_000L, drink.at)
    }

    @Test
    fun aBackupWrittenBeforeTheBookStillOpens() {
        // The shape 1.2.40 wrote: no drinks key at all.
        val text = """{"format":"wineverdict-backup","version":1,"appVersion":"1.2.40","currency":"USD","taste":"","cellar":{"wines":[{"id":"w1","producer":"","name":"Barolo","quantity":1}],"locations":[]}}"""

        val back = Backup.decode(text)

        assertEquals("Barolo", back.cellar.wines.first().name)
        assertTrue(back.drinks.isEmpty())
    }

    @Test
    fun aBookOnItsOwnIsNotAnEmptyBackup() {
        val text = Backup.encode(CellarData(), "", "1.2.41", "USD", listOf(night()))

        assertEquals(1, Backup.decode(text).drinks.size)
    }
}
