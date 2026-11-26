package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optional interview is data, so these guard the properties that keep it honest: each question
 * says what it is called in the brief, every chip that describes taste can be phrased, ids stay
 * unique the day someone adds a thirteenth, and answering none of it cannot make the profile look
 * less complete than the ten taps already made it.
 */
class QuickStartExtrasTest {

    @Test
    fun theInterviewIsTwelveQuestions() {
        assertEquals(12, QuickStart.extras.size)
        assertEquals(QuickStart.baseline.size + 12, QuickStart.questions.size)
    }

    @Test
    fun idsAreUniqueAcrossBaselineAndInterview() {
        val ids = QuickStart.questions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyExtraNamesItselfInTheBrief() {
        QuickStart.extras.forEach { question ->
            assertTrue("${question.id} has no briefLead", question.briefLead.isNotBlank())
            assertTrue("${question.id} offers no options", question.options.isNotEmpty())
        }
    }

    @Test
    fun everyChipThatDescribesTasteHasABriefPhrase() {
        QuickStart.extras.forEach { question ->
            val silent = question.options.filter { it.brief.isBlank() }
            // One silent option is allowed: "the names are still new to me", "no pattern", "nothing".
            // Two would mean a chip the model never hears about.
            assertTrue(
                "${question.id} has ${silent.size} unphrased options: ${silent.map { it.id }}",
                silent.size <= 1
            )
            assertTrue(
                "${question.id} phrases none of its options",
                question.options.any { it.brief.isNotBlank() }
            )
        }
    }

    @Test
    fun theInterviewNeverAsksToHoldUpTheShortlist() {
        QuickStart.extras.forEach { question ->
            assertFalse("${question.id} waits for the picks", question.afterPicks)
        }
    }

    @Test
    fun answeringTheInterviewDoesNotChangeTheBaselinePercent() {
        var profile = PalateProfile.EMPTY
        QuickStart.baseline.forEach { profile = profile.withAnswer(it.id, listOf(it.options.first().id)) }
        assertEquals(100, profile.knowledgePercent)

        var withExtras = profile
        QuickStart.extras.forEach { withExtras = withExtras.withAnswer(it.id, listOf(it.options.first().id)) }
        assertEquals(100, withExtras.knowledgePercent)
        assertEquals(QuickStart.extras.size, withExtras.extrasAnswered)
    }
}
