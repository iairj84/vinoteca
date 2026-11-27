package com.wineverdict.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One nudge exists at a time and it belongs to the newest list. A pick has to settle exactly that
 * one: settling everything would silence tonight's reminder because of a menu from last month, and
 * settling nothing is the nag this feature exists to remove.
 */
class ReminderTest {

    @Test
    fun aPickOnTheListsOwnMenuSettlesIt() {
        assertTrue(Reminders.settles("list-1", "list-1"))
    }

    @Test
    fun aPickOnAnOlderMenuLeavesTheReminderAlone() {
        assertFalse(Reminders.settles("list-2", "list-1"))
    }

    @Test
    fun nothingBookedMeansNothingToCancel() {
        assertTrue(Reminders.settles(null, "list-1"))
        assertTrue(Reminders.settles("", "list-1"))
    }

    @Test
    fun noListNamedCancelsWhateverIsPending() {
        // The unconditional form, for a caller that knows the reminder should go.
        assertTrue(Reminders.settles("list-2", null))
    }
}
