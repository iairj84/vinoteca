package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where a night was drunk.
 *
 * The place is optional, so most of these are about what an empty one must not say: no "at home",
 * no gap in the digest, nothing to search. The rest are about the place being the night's, and
 * travelling with the rest of the entry when the night is corrected.
 */
class JournalVenueTest {

    private val zone = ZoneId.systemDefault()

    private fun night(daysAgo: Long): Long =
        LocalDate.now().minusDays(daysAgo).atTime(20, 0)
            .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun aCorrectionCanMoveThePlaceAndTakeItAway() {
        val before = Drink(
            id = "a",
            title = "2019 Barolo",
            at = night(2),
            rating = Verdict.GOOD.rating,
            venue = "Bar Vin"
        )

        val moved = before.corrected(
            at = night(2),
            rating = Verdict.GOOD.rating,
            note = "",
            flavors = emptyList(),
            again = null,
            venue = "  Osteria Francescana  "
        )
        assertEquals("Osteria Francescana", moved.venue)

        val cleared = moved.corrected(
            at = night(2),
            rating = Verdict.GOOD.rating,
            note = "",
            flavors = emptyList(),
            again = null,
            venue = ""
        )
        assertEquals("", cleared.venue)
    }

    @Test
    fun theSearchFindsANightByWhereItWasDrunk() {
        val entry = Drink(title = "2019 Barolo", venue = "Café Mokka")
        assertTrue(matchesWords(entry, "mokka"))
        assertTrue(matchesWords(entry, "cafe mokka"))
        // Every word still has to match, and a place is not a bottle.
        assertFalse(matchesWords(entry, "barolo rioja"))
        assertFalse(matchesWords(entry, "osteria"))
    }

    @Test
    fun askIsToldThePlaceButOnlyWhenThereIsOne() {
        val atPlace = Drink(
            title = "2019 Barolo",
            rating = Verdict.LOVED.rating,
            venue = "Osteria Francescana",
            at = night(1)
        )
        val anywhere = Drink(title = "2020 Rioja", rating = Verdict.FINE.rating, at = night(1))

        val digest = drinkDigest(listOf(atPlace, anywhere), System.currentTimeMillis())
        assertTrue(digest.contains("at Osteria Francescana"))
        assertEquals("the wine with no place adds no words", 1, Regex(" at ").findAll(digest).count())
    }

    @Test
    fun anEntryFromBeforeTheFieldStillReads() {
        val old = """{"id":"a","title":"2019 Barolo","at":1,"via":"manual"}"""
        val drink = Json { ignoreUnknownKeys = true }.decodeFromString<Drink>(old)
        assertEquals("", drink.venue)
    }
}
