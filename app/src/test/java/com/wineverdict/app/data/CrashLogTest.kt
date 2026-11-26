package com.wineverdict.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CrashLogTest {

    @Test
    fun theReportCarriesTheStampTheBuildAndTheFrames() {
        val error = IllegalStateException("Progress must be between 0 and 1")
        val text = CrashLog.describe(
            error = error,
            header = "Wine Verdict 1.2.5 · Android 13 · thread main",
            at = Date(0)
        )
        assertTrue(text, text.contains("Wine Verdict 1.2.5"))
        assertTrue("which error", text.contains("java.lang.IllegalStateException"))
        assertTrue("what it said", text.contains("Progress must be between 0 and 1"))
        assertTrue("where it happened", text.contains("CrashLogTest"))
        assertTrue("a timestamp opens the report", text.lines().first().contains("·"))
    }

    @Test
    fun anEnormousTraceIsCutRatherThanKeptWhole() {
        val deep = generateSequence<Throwable>(IllegalStateException("top")) { cause ->
            RuntimeException("wrapped", cause)
        }.take(400).last()
        val text = CrashLog.describe(deep, header = "Wine Verdict 1.2.5")
        assertTrue("kept under the cap: ${text.length}", text.length <= 6_000)
        // The head is what identifies the failure: the deepest frames are what gets dropped.
        assertTrue(text, text.contains("RuntimeException: wrapped"))
        assertTrue(text.lines().first().contains("Wine Verdict 1.2.5"))
    }
}
