package com.wineverdict.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nudge is the only thing that ever asks for a backup, so it has to be quiet when a copy is
 * fresh and impossible to miss when there is none — and **Not now** has to buy a month, not silence.
 */
class BackupNudgeTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun aCellarNeverBackedUpAsksForOne() {
        assertTrue(BackupNudge.due(true, lastBackupAt = 0L, dismissedAt = 0L, now = now))
    }

    @Test
    fun anEmptyCellarAsksForNothing() {
        assertFalse(BackupNudge.due(false, lastBackupAt = 0L, dismissedAt = 0L, now = now))
    }

    @Test
    fun aFreshBackupAsksForNothing() {
        assertFalse(BackupNudge.due(true, lastBackupAt = now - 3 * day, dismissedAt = 0L, now = now))
    }

    @Test
    fun aMonthOldBackupAsksAgain() {
        assertTrue(BackupNudge.due(true, lastBackupAt = now - 31 * day, dismissedAt = 0L, now = now))
    }

    @Test
    fun notNowBuysAMonth() {
        assertFalse(BackupNudge.due(true, lastBackupAt = 0L, dismissedAt = now - 2 * day, now = now))
        assertTrue(BackupNudge.due(true, lastBackupAt = 0L, dismissedAt = now - 31 * day, now = now))
    }

    @Test
    fun theWordsNameTheLastBackup() {
        assertTrue(BackupNudge.words(0L, now).contains("never left"))
        assertTrue(BackupNudge.words(now - 45 * day, now).contains("45 days"))
        assertTrue(BackupNudge.words(now - 90 * day, now).contains("3 months"))
    }
}
