package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 4: the three answers only the user can give.
 *
 * They are optional, they are verbatim, and skipping them changes nothing else — no rule, no gate,
 * no line in the brief. Each is capped at 200 characters because the brief has a hard budget.
 */
class TasteNotesTest {

    private fun profile(
        loved: String = "",
        disliked: String = "",
        curious: String = "",
        links: String = ""
    ) = PalateProfile(
        answers = mapOf(QuickStart.Q_POURS to listOf(QuickStart.POUR_RED)),
        lovedNote = loved,
        dislikedNote = disliked,
        curiousNote = curious,
        links = links
    )

    private fun brief(p: PalateProfile, links: String? = null) =
        PalateFile.brief(profile = p, loved = emptyList(), disliked = emptyList(), links = links)

    @Test
    fun theAnswersRideInTheBriefVerbatim() {
        val text = brief(
            profile(
                loved = "2019 Ultreia — dark fruit, and it smelled of the forest",
                disliked = "an oaky Chardonnay that tasted of vanilla",
                curious = "orange wine, or anything from Bierzo"
            )
        )
        assertTrue(text.contains("2019 Ultreia — dark fruit, and it smelled of the forest"))
        assertTrue(text.contains("an oaky Chardonnay that tasted of vanilla"))
        assertTrue(text.contains("orange wine, or anything from Bierzo"))
    }

    @Test
    fun anAnswerIsCappedWhenItIsStored() {
        val stored = profile().withNotes(loved = "x".repeat(500))
        assertEquals(PalateProfile.NOTE_MAX_CHARS, stored.lovedNote.length)
    }

    @Test
    fun anOverlongAnswerIsCappedWhenItIsRendered() {
        // A hand-edited profile can be any length; the brief caps it on the way in, too.
        val text = brief(profile(loved = "x".repeat(500)))
        assertTrue(text.contains("x".repeat(PalateProfile.NOTE_MAX_CHARS)))
        assertFalse(text.contains("x".repeat(PalateProfile.NOTE_MAX_CHARS + 1)))
    }

    @Test
    fun theLinksLineComesFromTheProfile() {
        assertTrue(
            brief(profile(links = "smooth reds with acid, never jammy"))
                .contains("What links them: smooth reds with acid, never jammy")
        )
    }

    @Test
    fun anExplicitLinksArgumentWins() {
        val text = brief(profile(links = "profile words"), links = "argument words")
        assertTrue(text.contains("argument words"))
        assertFalse(text.contains("profile words"))
    }

    @Test
    fun skippingThemChangesNothing() {
        val text = brief(profile())
        assertFalse(text.contains("Wines I loved"))
        assertFalse(text.contains("Curious to try"))
        assertFalse(text.contains("What links them"))
    }

    @Test
    fun theFileCarriesThemToo() {
        val text = PalateFile.build(profile(links = "smooth reds", loved = "Ultreia was the one"))
        assertTrue(text.contains("## In my own words"))
        assertTrue(text.contains("Ultreia was the one"))
    }

    @Test
    fun aCeilingIsWrittenAsTheUsersNumber() {
        val rules = PalateRules(maxAlcohol = 13.5)
        val text = PalateFile.build(profile(), rules, ratings = 2)
        assertTrue(text.contains("13.5% or under"))
        assertTrue("the number is the user's, not ours", text.contains("typed on the Taste screen"))
        assertFalse(text.contains("the number is ours"))
    }
}
