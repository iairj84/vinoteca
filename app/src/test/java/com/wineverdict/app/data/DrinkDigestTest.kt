package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The drink log's prompt digest. The wording is the feature: "yesterday" is the word the question
 * uses, and it is worked out here rather than left to a model reading a timestamp.
 */
class DrinkDigestTest {

    private fun at(daysAgo: Long, hour: Int = 20): Long =
        LocalDate.now()
            .minusDays(daysAgo)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun theDayIsNamedAndTheWeekIsCounted() {
        val drinks = listOf(
            Drink(
                id = "a",
                title = "2024 Gran Moraine",
                at = at(1),
                rating = Verdict.LOVED.rating,
                note = "thirty-minute decant"
            ),
            Drink(id = "b", title = "2020 Rioja", at = at(10), rating = Verdict.FINE.rating)
        )

        val digest = drinkDigest(drinks, System.currentTimeMillis())

        assertTrue(digest, digest.contains("yesterday: 2024 Gran Moraine"))
        assertTrue(digest, digest.contains("Loved this week: 2024 Gran Moraine"))
        assertTrue(digest, digest.contains("1 bottle(s) in the last 7 days"))
        assertTrue(digest, digest.contains("2 in the last 30"))
        assertTrue(digest, digest.contains("thirty-minute decant"))
    }

    @Test
    fun aDrinkWithNoVerdictStillReadsAsADrink() {
        val digest = drinkDigest(
            listOf(Drink(title = "2022 Chablis", at = at(2))),
            System.currentTimeMillis()
        )
        assertTrue(digest, digest.contains("no verdict"))
    }

    @Test
    fun anEmptyRecordSaysNothingAtAll() {
        // Blank, not "nothing yet": the prompt supplies that sentence, and it has to be able to tell
        // an install that never drank from one whose record happens to be empty.
        assertEquals("", drinkDigest(emptyList(), System.currentTimeMillis()))
    }

    @Test
    fun theLineIsLimitedButTheTotalsAreNot() {
        val drinks = (0 until 30).map { day ->
            Drink(id = "$day", title = "Wine $day", at = at(day.toLong()))
        }
        val digest = drinkDigest(drinks, System.currentTimeMillis(), limit = 5)

        assertTrue(digest, digest.contains("Wine 0"))
        assertTrue(digest, digest.contains("Wine 4"))
        assertTrue("beyond the line limit", !digest.contains("Wine 12"))
        // Day 29 at 20:00 is under 30 days back whatever the hour: 30 entries, five lines.
        assertTrue(digest, digest.contains("30 in the last 30"))
    }
}
