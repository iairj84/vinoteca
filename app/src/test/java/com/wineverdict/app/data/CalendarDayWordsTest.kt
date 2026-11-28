package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * What a day of the calendar says out loud.
 *
 * The dot under a number is the whole of what marks a night for sighted users, and a screen reader
 * cannot hear it, so the sentence has to carry the date, the count and today by itself.
 */
class CalendarDayWordsTest {

    private val day = LocalDate.of(2025, 11, 27)
    private val en = Locale.UK

    @Test
    fun anEmptyDaySaysItsDateAndThatNothingWasWritten() {
        assertEquals(
            "Thursday 27 November 2025, no entries",
            calendarDayWords(day, count = 0, today = false, locale = en)
        )
    }

    @Test
    fun aNightSaysHowManyEntriesItHolds() {
        assertEquals(
            "Thursday 27 November 2025, 1 entry",
            calendarDayWords(day, count = 1, today = false, locale = en)
        )
        assertEquals(
            "Thursday 27 November 2025, 2 entries",
            calendarDayWords(day, count = 2, today = false, locale = en)
        )
    }

    @Test
    fun todaySaysSoRatherThanLeavingItToAHighlight() {
        assertEquals(
            "Thursday 27 November 2025, 2 entries, today",
            calendarDayWords(day, count = 2, today = true, locale = en)
        )
    }
}
