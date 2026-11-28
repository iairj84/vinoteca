package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the drink-up view shows and what the weekly nudge says.
 *
 * The two rules the view leans on are that only stock counts and that **drink now** is not urgent —
 * both are here, because a view that named half the cellar would be a second copy of the cellar.
 */
class DrinkUpTest {

    private fun wine(
        name: String,
        from: Int? = null,
        to: Int? = null,
        quantity: Int = 1,
        producer: String = ""
    ) = Wine(
        producer = producer,
        name = name,
        quantity = quantity,
        drinkFrom = from,
        drinkTo = to
    )

    @Test
    fun onlyPeakAndPastAreWanted() {
        val year = 2026
        val bottles = listOf(
            wine("Still holding", 2030, 2040),
            wine("Simply ready", 2020, 2030),
            wine("At its peak", 2015, 2026),
            wine("Past it", 2005, 2015),
            wine("No window")
        )

        val due = DrinkUp.bottles(bottles, year)

        assertEquals(listOf("Past it", "At its peak"), due.map { it.name })
    }

    @Test
    fun aBottleWithNoneLeftIsNotADecision() {
        val bottles = listOf(
            wine("Drank it all", 2005, 2015, quantity = 0),
            wine("One left", 2005, 2015, quantity = 1)
        )

        assertEquals(listOf("One left"), DrinkUp.bottles(bottles, 2026).map { it.name })
    }

    @Test
    fun theNamesAreCappedAndTheRestAreCounted() {
        val bottles = (1..5).map { wine("Bottle $it", 2005, 2015) }

        val digest = DrinkUp.digest(bottles, 2026)!!

        assertEquals("5 bottles past their window", digest.title)
        assertTrue(digest.body, digest.body.contains("Bottle 1 · Bottle 2 · Bottle 3 and 2 more"))
        assertTrue(digest.body, digest.body.contains("5 past their window"))
    }

    @Test
    fun oneBottleIsSaidInTheSingular() {
        val peak = DrinkUp.digest(listOf(wine("2019 Barolo", 2015, 2026)), 2026)!!
        assertEquals("One bottle at its peak", peak.title)
        assertTrue(peak.body, peak.body.contains("one at its peak"))

        val past = DrinkUp.digest(listOf(wine("2013 Rioja", 2005, 2015)), 2026)!!
        assertEquals("One bottle past its window", past.title)
    }

    @Test
    fun aMixedWeekCountsBothKinds() {
        val digest = DrinkUp.digest(
            listOf(
                wine("Old one", 2005, 2015),
                wine("Peak one", 2015, 2026),
                wine("Other peak", 2016, 2026)
            ),
            2026
        )!!

        assertEquals("3 bottles to drink up", digest.title)
        assertTrue(digest.body, digest.body.contains("one past its window, 2 at their peak"))
    }

    @Test
    fun nothingToDrinkUpSaysNothingAtAll() {
        // The nudge stays worth leaving on precisely because it is silent on a week with no news.
        assertNull(DrinkUp.digest(listOf(wine("Ready", 2020, 2030)), 2026))
        assertNull(DrinkUp.digest(emptyList(), 2026))
    }

    @Test
    fun theNudgeLandsOnMondayMorning() {
        val zone = ZoneId.of("Europe/London")

        // Sunday evening: tomorrow morning is the next one.
        assertEquals(
            ZonedDateTime.of(2026, 11, 30, 9, 0, 0, 0, zone),
            WeeklyDigest.nextAfter(ZonedDateTime.of(2026, 11, 29, 21, 30, 0, 0, zone))
        )
        // Monday before nine: today.
        assertEquals(
            ZonedDateTime.of(2026, 11, 30, 9, 0, 0, 0, zone),
            WeeklyDigest.nextAfter(ZonedDateTime.of(2026, 11, 30, 7, 15, 0, 0, zone))
        )
        // Monday after nine: a week away, never a nudge in the past.
        assertEquals(
            ZonedDateTime.of(2026, 12, 7, 9, 0, 0, 0, zone),
            WeeklyDigest.nextAfter(ZonedDateTime.of(2026, 11, 30, 10, 0, 0, 0, zone))
        )
        // And a Wednesday is still next Monday, not tomorrow.
        val wednesday = ZonedDateTime.of(2026, 12, 2, 12, 0, 0, 0, zone)
        assertEquals(DayOfWeek.MONDAY, WeeklyDigest.nextAfter(wednesday).dayOfWeek)
    }

    @Test
    fun theDelayIsTheDistanceToIt() {
        val zone = ZoneId.of("Europe/London")
        val mondayMorning = ZonedDateTime.of(2026, 11, 30, 9, 0, 0, 0, zone)

        assertEquals(1L, WeeklyDigest.delayMillis(mondayMorning.minusNanos(1_000_000)))
        assertEquals(
            7L * 24 * 60 * 60 * 1000,
            WeeklyDigest.delayMillis(mondayMorning)
        )
    }
}
