package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Completing the quick start regenerates `mytaste.md`, and the first version of that dropped the
 * seeded "## Notes for the assistant" lines — which the brief sends. These pin the carry-over: it
 * survives a rewrite, it is not duplicated when the new file already has one, and a file whose notes
 * were deleted does not get them back.
 */
class TasteAssistantNotesTest {

    private val profile = PalateProfile(
        answers = mapOf(QuickStart.Q_POURS to listOf(QuickStart.POUR_RED))
    )

    private fun regenerated() = PalateFile.build(profile, ratings = 0)

    @Test
    fun theSeededNotesSurviveARewrite() {
        val seed = TasteStore.DEFAULT_TEMPLATE
        val kept = TasteText.keepAssistantNotes(regenerated(), seed)

        assertTrue(kept, kept.contains(TasteText.ASSISTANT_NOTES))
        assertTrue(kept, kept.contains("Prefer bottles I already own"))
        assertTrue(kept, kept.contains("past its window, say so plainly"))
    }

    @Test
    fun theCarriedNotesStillReachTheBrief() {
        // The point of keeping them: they are what every request reads as standing instructions.
        val kept = TasteText.keepAssistantNotes(regenerated(), TasteStore.DEFAULT_TEMPLATE)
        val notes = TasteText.userNotes(kept)
        assertTrue(notes, notes.contains("Prefer bottles I already own"))
        assertTrue(notes, notes.contains("say so plainly"))
    }

    @Test
    fun aFileWhoseNotesWereDeletedStaysDeleted() {
        val seed = TasteStore.DEFAULT_TEMPLATE
        val edited = seed.replace(TasteText.ASSISTANT_NOTES, "## Nothing of mine")
        val kept = TasteText.keepAssistantNotes(regenerated(), edited)

        assertFalse(kept, kept.contains(TasteText.ASSISTANT_NOTES))
        assertFalse(kept, kept.contains("Prefer bottles I already own"))
    }

    @Test
    fun theBlockIsNotDuplicatedWhenTheNewFileAlreadyHasOne() {
        val body = regenerated().trimEnd() + "\n\n" + TasteText.ASSISTANT_NOTES + "\n- Keep it short.\n"
        val kept = TasteText.keepAssistantNotes(body, TasteStore.DEFAULT_TEMPLATE)

        assertEquals(1, Regex(Regex.escape(TasteText.ASSISTANT_NOTES)).findAll(kept).count())
        assertTrue(kept, kept.contains("Keep it short."))
    }

    @Test
    fun onlyTheAssistantSectionIsRead() {
        assertEquals("", TasteText.assistantNotes("# My taste\n\n## What I enjoy\n- red"))
        assertEquals(
            "## Notes for the assistant\n- Prefer bottles I own.",
            TasteText.assistantNotes("## What I enjoy\n- red\n\n## Notes for the assistant\n- Prefer bottles I own.\n\n## Next\n- x")
        )
    }
}
