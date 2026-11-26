package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Completing the quick start regenerates `mytaste.md`, and the first version of that dropped the
 * seeded "## Notes for the assistant" lines — which the brief sends. A hand-edited or imported file
 * keeps its own text under "## Notes from my previous file (kept)", which is the same problem one
 * step later. These pin the carry-over for both: it survives a rewrite, it is not duplicated when
 * the new file already has the heading, and a file whose notes were deleted does not get them back.
 */
class TasteAssistantNotesTest {

    private val profile = PalateProfile(
        answers = mapOf(QuickStart.Q_POURS to listOf(QuickStart.POUR_RED))
    )

    private fun regenerated() = PalateFile.build(profile, ratings = 0)

    private fun keptFile() = """
        # My taste

        ## What I enjoy
        - **Body:** medium to full

        ## Notes from my previous file (kept)

        # My taste
        **Anchor line:** "Pinot's lift with more grip."
        - Herbal, peppery, cool-site wine.
    """.trimIndent()

    @Test
    fun theSeededNotesSurviveARewrite() {
        val seed = TasteStore.DEFAULT_TEMPLATE
        val kept = TasteText.keepNoteSections(regenerated(), seed)

        assertTrue(kept, kept.contains(TasteText.ASSISTANT_NOTES))
        assertTrue(kept, kept.contains("Prefer bottles I already own"))
        assertTrue(kept, kept.contains("past its window, say so plainly"))
    }

    @Test
    fun theCarriedNotesStillReachTheBrief() {
        // The point of keeping them: they are what every request reads as standing instructions.
        val kept = TasteText.keepNoteSections(regenerated(), TasteStore.DEFAULT_TEMPLATE)
        val notes = TasteText.userNotes(kept)
        assertTrue(notes, notes.contains("Prefer bottles I already own"))
        assertTrue(notes, notes.contains("say so plainly"))
    }

    @Test
    fun theSectionAHandEditedFileKeptSurvives() {
        val kept = TasteText.keepNoteSections(regenerated(), keptFile())

        assertTrue(kept, kept.contains("## Notes from my previous file (kept)"))
        assertTrue(kept, kept.contains("\"Pinot's lift with more grip.\""))
        // The pasted file's own H1 is dropped: the carried block already has a heading above it.
        assertFalse(kept, kept.contains("# My taste\n**Anchor line"))
        assertTrue(TasteText.userNotes(kept).contains("Herbal, peppery"))
    }

    @Test
    fun aFileWhoseNotesWereDeletedStaysDeleted() {
        val edited = TasteStore.DEFAULT_TEMPLATE
            .replace(TasteText.ASSISTANT_NOTES, "## Nothing of mine")
        val kept = TasteText.keepNoteSections(regenerated(), edited)

        assertFalse(kept, kept.contains(TasteText.ASSISTANT_NOTES))
        assertFalse(kept, kept.contains("Prefer bottles I already own"))
    }

    @Test
    fun aBlockIsNotDuplicatedWhenTheNewFileAlreadyHasOne() {
        val body = regenerated().trimEnd() + "\n\n" + TasteText.ASSISTANT_NOTES + "\n- Keep it short.\n"
        val kept = TasteText.keepNoteSections(body, TasteStore.DEFAULT_TEMPLATE)

        assertEquals(1, Regex(Regex.escape(TasteText.ASSISTANT_NOTES)).findAll(kept).count())
        assertTrue(kept, kept.contains("Keep it short."))
    }

    @Test
    fun onlyNoteSectionsAreRead() {
        assertEquals(emptyList<String>(), TasteText.noteSections("# My taste\n\n## What I enjoy\n- red"))

        val kept = TasteText.noteSections(keptFile())
        assertEquals(1, kept.size)
        assertTrue(kept.first(), kept.first().startsWith("## Notes from my previous file (kept)"))
        assertFalse(kept.first(), kept.first().contains("Body: medium to full"))
    }

    @Test
    fun theBriefAndTheCarryOverReadTheSameSections() {
        val file = keptFile() + "\n\n" + TasteText.ASSISTANT_NOTES + "\n- Prefer bottles I own.\n"
        val briefNotes = TasteText.userNotes(file)
        val carried = TasteText.keepNoteSections("body", file)

        TasteText.noteSections(file).forEach { section ->
            val body = section.substringAfter('\n').take(30)
            assertTrue("brief lost: $body", briefNotes.contains(body))
            assertTrue("carry-over lost: $body", carried.contains(body))
        }
    }
}
