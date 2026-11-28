package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The night a year earlier.
 *
 * The calendar points at an evening and the book answers with the same date a year before: a
 * different fact from "365 days ago", which is the one thing that changes around a leap year, and
 * one that must not name nights from any other date.
 */
class JournalYearAgoTest {

    private val zone = ZoneId.systemDefault()

    private fun at(date: LocalDate, hour: Int = 20): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theSameDateAYearEarlierIsTheNight() {
        val drinks = listOf(
            Drink(id = "a", title = "2025 Barolo", at = at(LocalDate.of(2025, 3, 1))),
            Drink(id = "b", title = "2025 Rioja", at = at(LocalDate.of(2025, 2, 28))),
            Drink(id = "c", title = "This year", at = at(LocalDate.of(2026, 3, 1))),
            Drink(id = "d", title = "Two years back", at = at(LocalDate.of(2024, 3, 1)))
        )

        val found = yearAgo(drinks, LocalDate.of(2026, 3, 1), zone)

        assertEquals(listOf("a"), found.map { it.id })
    }

    @Test
    fun aLeapDayLooksBackToTheLastDayOfFebruary() {
        // 2027 has no 29 February; the anniversary is the 28th, not 1 March.
        val drinks = listOf(
            Drink(id = "a", title = "2027 Barolo", at = at(LocalDate.of(2027, 2, 28))),
            Drink(id = "b", title = "2027 March", at = at(LocalDate.of(2027, 3, 1)))
        )

        val found = yearAgo(drinks, LocalDate.of(2028, 2, 29), zone)

        assertEquals(listOf("a"), found.map { it.id })
    }

    @Test
    fun theLineNamesThreeThenCountsTheRest() {
        val entries = listOf(
            Drink(title = "2019 Barolo", rating = Verdict.LOVED.rating),
            Drink(title = "2020 Rioja", rating = Verdict.GOOD.rating),
            Drink(title = "NV Champagne"),
            Drink(title = "2022 Chablis", rating = Verdict.FINE.rating)
        )

        assertEquals(
            "2019 Barolo — Loved · 2020 Rioja — Good · NV Champagne and 1 more",
            yearAgoWords(entries)
        )
    }

    @Test
    fun aNightWithNothingSaidIsNamedWithoutAVerdict() {
        assertEquals(
            "NV Champagne",
            yearAgoWords(listOf(Drink(title = "NV Champagne", via = Drink.COUNT)))
        )
        assertEquals("", yearAgoWords(emptyList()))
    }
}
